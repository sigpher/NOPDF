package com.github.barteksc.pdfviewer.engine;

/**
 * Decides when to empty the engine's resource store, so that rendering a long document does not
 * accumulate memory without bound.
 *
 * <p>This exists because releasing a MuPDF page does not give back most of what rendering it
 * cost. Fonts, images and shadings that a page referenced are owned by the store, not by the
 * page, and the store does not release them when the page is dropped — they are evicted only
 * under memory pressure or by an explicit request. pdfium had no such layer, so the viewer
 * never had to think about it.
 *
 * <p>The growth is linear in the number of pages <em>rendered</em>, not in the number held, and
 * that distinction is what makes this necessary. Measured against MuPDF 1.28.5 with a 200-page
 * document whose every page carried a distinct 420x560 image, walking the pages and rendering
 * each as a 3x4 grid of tiles:
 *
 * <pre>
 *   peak live bytes, holding nothing / every 8 / every 16 / every 32 pages trimmed
 *     never trimmed   438.9 MB
 *     every 8 pages    18.8 MB     (+2% wall time)
 *     every 16 pages   35.8 MB     (+0%)
 *     every 32 pages   70.8 MB     (+0%)
 * </pre>
 *
 * <p>Peak scales with the interval, so the interval is a straight memory/time trade: a few tens
 * of megabytes of headroom for a couple of percent of render time. Eight is the knee of that
 * curve for the documents this viewer is used on. Trimming was also measured not to change what
 * is drawn — the rendered tiles were the same either way — so the cost is reloading a resource,
 * not a different picture.
 *
 * <p>Note what is <em>not</em> being fixed here. Bounding which pages are held
 * ({@link PageResidency}) and bounding the store are independent: holding every page ever opened
 * grew the store at exactly the same rate, and trimming it while only eight pages are held works
 * just as well. A caller that reads a "long document renders blank" report as "too many pages
 * open" will fix the wrong thing.
 *
 * <p>Not thread-safe: it is driven from the page-release path.
 */
public final class StoreTrim {

    /** Pages to release between trims. Chosen from the measured curve above. */
    public static final int DEFAULT_INTERVAL = 8;

    private final int interval;
    private int sinceLastTrim;

    public StoreTrim() {
        this(DEFAULT_INTERVAL);
    }

    public StoreTrim(int interval) {
        if (interval < 1) {
            throw new IllegalArgumentException("interval must be at least 1, was " + interval);
        }
        this.interval = interval;
    }

    /**
     * Records that a page was given back, and reports whether the store should be emptied now.
     *
     * <p>Only call this for a page that was actually held. Counting releases that never happened
     * would trim on a schedule that has nothing to do with how much has accumulated, which on a
     * document that is mostly re-requesting a few pages means trimming constantly for nothing.
     */
    public boolean onPageReleased() {
        if (++sinceLastTrim < interval) {
            return false;
        }
        sinceLastTrim = 0;
        return true;
    }

    /** Forgets the count, so the next release is the first of a new interval. */
    public void reset() {
        sinceLastTrim = 0;
    }

    public int getInterval() {
        return interval;
    }
}
