package com.github.barteksc.pdfviewer.engine;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The viewer's policy for how many pages the engine may hold open at once, and which ones to
 * give up when that many are reached.
 *
 * <p>This exists because the two engines differ in what an open page costs, and only the engine
 * knows. pdfium handed out a native handle that was nearly free to keep, so pages could simply
 * be opened once each and left alone. MuPDF allocates a real page object per load — parsed
 * contents, resources and all — and the memory only comes back when it is released, so "open
 * every page you ever visit" made the resident set grow with the length of the scroll until
 * loading a page started failing. So the residency bound has to be maintained by the caller,
 * against {@link PdfEngine#openPage} and {@link PdfEngine#closePage}.
 *
 * <p>Two failure modes are worth naming, because both end as pages that render nothing at all:
 *
 * <ul>
 *   <li>Holding pages without a bound, so the engine eventually fails to load one. Left to
 *       {@link PdfEngine} alone, a failure is indistinguishable from a page that is merely not
 *       held right now, and treating it as either is wrong: treating it as fine re-renders in a
 *       loop, treating it as broken leaves a blank page behind permanently.
 *   <li>Forgetting a failure and never retrying. A page that failed to open almost always failed
 *       because the heap was exhausted at that instant, so a failure flag that outlives the
 *       condition that caused it converts a transient fault into a permanently blank page. It
 *       is dropped here when the page ages out, which bounds the damage to the handful of pages
 *       currently held.
 * </ul>
 *
 * <p>Not thread-safe: it is driven entirely from the render thread.
 */
public final class PageResidency {

    /** Hands a page back to the engine. */
    public interface Releaser {
        void release(int pageIndex);
    }

    private final int max;
    private final Releaser releaser;
    /**
     * Pages currently held, in least recently used order; the value is whether opening it
     * failed. Access-ordered, so every read moves a page to the young end and iterating yields
     * eviction order.
     */
    private final LinkedHashMap<Integer, Boolean> held = new LinkedHashMap<>(16, 0.75f, true);

    public PageResidency(int max, Releaser releaser) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1, was " + max);
        }
        this.max = max;
        this.releaser = releaser;
    }

    /**
     * Whether the page is currently held, either opened successfully or known to have failed.
     * Counts as use, so a page that keeps being drawn survives trimming.
     */
    public boolean isHeld(int pageIndex) {
        // get() and not containsKey(): in an access-ordered map only get() counts as use
        // (containsKey is a plain lookup in the JDK), and that refresh is the whole reason to
        // ask. A page that is on screen is asked about constantly, and it is exactly those
        // that must not be trimmed out from under it.
        return held.get(pageIndex) != null;
    }

    /**
     * Whether an attempt to open this page failed and the failure has not been forgotten yet.
     *
     * <p>A page that is not held is <em>not</em> a failure — it has either never been visited or
     * was given back to the engine, and in both cases rendering it should be allowed to try.
     */
    public boolean hasFailed(int pageIndex) {
        return Boolean.TRUE.equals(held.get(pageIndex));
    }

    /** Records the outcome of an open attempt, making this page the most recently used. */
    public void record(int pageIndex, boolean failed) {
        held.put(pageIndex, failed);
    }

    /** Gives the least recently used pages back until at most {@code max} are held. */
    public void trim() {
        Iterator<Map.Entry<Integer, Boolean>> iterator = held.entrySet().iterator();
        while (held.size() > max && iterator.hasNext()) {
            Map.Entry<Integer, Boolean> eldest = iterator.next();
            iterator.remove();
            if (!eldest.getValue()) {
                // A page that never opened holds nothing, so there is nothing to give back.
                releaser.release(eldest.getKey());
            }
        }
    }

    /** How many pages are held, open or failed. */
    public int size() {
        return held.size();
    }

    public void clear() {
        held.clear();
    }
}
