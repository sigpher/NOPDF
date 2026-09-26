package com.github.barteksc.pdfviewer.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;
import android.util.SparseArray;

import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.LinkDestination;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Outline;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.SeekableInputStream;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The MuPDF-backed {@link PdfEngine}.
 *
 * <p>Built against MuPDF 1.28.5's Java bindings ({@code com.artifex.mupdf.fitz}). Unlike
 * pdfium, MuPDF has no file-descriptor entry point and no password exception, allocates a
 * fresh {@code Page} on every {@code loadPage}, and reports table-of-contents entries as a
 * tree of {@link Outline} that carries no page number. Each of those is bridged here; see the
 * individual methods for how.
 */
class MupdfEngine implements PdfEngine {

    /**
     * Per-document state. MuPDF hands out an independently allocated {@code Page} per
     * {@code loadPage} call, and callers above this layer are written against pdfium's model
     * where a page is opened once and then rendered repeatedly ({@code PdfFile} keeps an
     * {@code openedPages} set for exactly that reason). The cache reconciles the two: opening
     * a page loads it once, and every later render of that page reuses the handle.
     *
     * <p>Entries are released by {@link #closeDocument}. Nothing here evicts on its own — the
     * bound on how many pages are resident comes from the caller, which only opens the pages
     * it intends to keep around. (Measuring a page is the exception: {@link #getPageSize}
     * must not add to this map, or setting up a document would load every page in it.)
     *
     * <p>All access to {@code pages} is guarded by the instance monitor. Unlike pdfium, whose
     * per-page state lived behind native handles, this map is plain Java shared between the
     * renderer thread (which opens and renders pages) and the main thread (which measures
     * pages and maps hit rectangles for link handling), and {@code SparseArray} is not
     * thread-safe. The monitor is only ever held while the map is read or written, never
     * across an actual render, so rendering pages does not serialise on it.
     */
    private static final class MuDoc {
        final Document document;
        private final SparseArray<Page> pages = new SparseArray<>();

        MuDoc(Document document) {
            this.document = document;
        }

        synchronized Page page(int pageIndex) {
            Page page = pages.get(pageIndex);
            if (page == null) {
                page = document.loadPage(pageIndex);
                pages.put(pageIndex, page);
            }
            return page;
        }

        synchronized Page cachedPage(int pageIndex) {
            return pages.get(pageIndex);
        }

        synchronized void destroyPages() {
            for (int i = 0; i < pages.size(); i++) {
                pages.valueAt(i).destroy();
            }
            pages.clear();
        }
    }

    MupdfEngine(Context context) {
        // Fitz 的初始化是进程级的，且幂等；此处只保证在任何 API 调用前完成。
        com.artifex.mupdf.fitz.Context.init();
    }

    @Override
    public String getName() {
        return "mupdf";
    }

    /**
     * MuPDF cannot open from a raw file descriptor, so the descriptor is wrapped in a
     * seekable stream over the same fd. Going through the stream rather than reading the file
     * into a byte[] matters: PDFs here routinely run to hundreds of megabytes.
     *
     * <p>MuPDF's own {@code FitzInputStream} cannot be used for this — its constructor is
     * private, so it is only ever produced from native code.
     */
    @Override
    public EngineDocument openDocument(ParcelFileDescriptor pfd, String password) throws IOException {
        FileInputStream stream = new FileInputStream(pfd.getFileDescriptor());
        return finishOpen(Document.openDocument(new FdStream(stream), MIME_PDF), password);
    }

    /** Adapts a seekable file descriptor to the three-method interface MuPDF asks for. */
    private static final class FdStream implements SeekableInputStream {
        private final FileInputStream stream;

        FdStream(FileInputStream stream) {
            this.stream = stream;
        }

        @Override
        public long seek(long offset, int whence) throws IOException {
            long target;
            switch (whence) {
                case SEEK_SET:
                    target = offset;
                    break;
                case SEEK_CUR:
                    target = position() + offset;
                    break;
                case SEEK_END:
                    target = stream.getChannel().size() + offset;
                    break;
                default:
                    throw new IOException("Bad whence: " + whence);
            }
            stream.getChannel().position(target);
            return target;
        }

        @Override
        public long position() throws IOException {
            return stream.getChannel().position();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return stream.read(b);
        }
    }

    @Override
    public EngineDocument openDocument(byte[] bytes, String password) throws IOException {
        return finishOpen(Document.openDocument(bytes, MIME_PDF), password);
    }

    /**
     * MuPDF signals a wrong or missing password by return value, not by throwing. Translating
     * it here keeps {@link PasswordRequiredException} the single representation of that
     * condition for everything above the engine boundary.
     */
    private EngineDocument finishOpen(Document document, String password) throws IOException {
        if (document.needsPassword() && !document.authenticatePassword(password == null ? "" : password)) {
            document.destroy();
            throw new PasswordRequiredException();
        }
        return new EngineDocument(this, new MuDoc(document));
    }

    @Override
    public void closeDocument(EngineDocument handle) {
        MuDoc muDoc = muDoc(handle);
        muDoc.destroyPages();
        muDoc.document.destroy();
    }

    @Override
    public int getPageCount(EngineDocument handle) {
        return muDoc(handle).document.countPages();
    }

    /**
     * Uses the crop box, matching what pdfium's {@code getPageSize} reports.
     *
     * <p>Deliberately does <em>not</em> go through {@link #page}: the viewer asks for the
     * size of <em>every</em> page while setting a document up ({@code PdfFile.setup}), so
     * caching here would make opening an N-page document retain N resident MuPDF pages.
     * pdfium answered this from an index-based native call that opened nothing; MuPDF has
     * no such entry point, so the page is loaded, measured, and released again. Pages
     * that are already resident (because they are open for rendering) are measured
     * through the cache instead.
     */
    @Override
    public EngineSize getPageSize(EngineDocument handle, int pageIndex) {
        MuDoc muDoc = muDoc(handle);
        Page resident = muDoc.cachedPage(pageIndex);
        if (resident != null) {
            return boundsSize(resident);
        }
        Page page = muDoc.document.loadPage(pageIndex);
        try {
            return boundsSize(page);
        } finally {
            page.destroy();
        }
    }

    private static EngineSize boundsSize(Page page) {
        com.artifex.mupdf.fitz.Rect bounds = page.getBounds();
        return new EngineSize(Math.round(bounds.x1 - bounds.x0), Math.round(bounds.y1 - bounds.y0));
    }

    @Override
    public void openPage(EngineDocument handle, int pageIndex) {
        page(handle, pageIndex);
    }

    /**
     * Renders the page-space rectangle {@code bounds} (in points) into {@code bitmap}.
     *
     * <p>MuPDF's draw device takes a device-space patch rather than a page-space source
     * rectangle, so the page region is instead expressed as a transform: the region is scaled
     * to the bitmap and shifted so that its top-left lands on the bitmap origin. The device
     * then covers the whole bitmap.
     */
    @Override
    public void renderPageBitmap(EngineDocument handle, Bitmap bitmap, int pageIndex, Rect bounds,
                                 boolean annotationRendering) {
        float regionWidth = bounds.width();
        float regionHeight = bounds.height();
        if (regionWidth <= 0 || regionHeight <= 0) {
            return;
        }
        Page page = page(handle, pageIndex);
        float scale = Math.min(bitmap.getWidth() / regionWidth, bitmap.getHeight() / regionHeight);
        Matrix ctm = new Matrix(scale, 0f, 0f, scale, -bounds.left * scale, -bounds.top * scale);
        AndroidDrawDevice device = new AndroidDrawDevice(bitmap, 0, 0, true);
        try {
            // MuPDF draws annotations as part of run(); pdfium's flag only gated them, and
            // this viewer always renders with them, so the flag has no separate handling.
            page.run(device, ctm, null);
        } finally {
            device.destroy();
        }
    }

    /**
     * MuPDF exposes metadata as individual info-dictionary lookups rather than a metadata
     * object, so the eight fields the neutral type carries are fetched by key.
     */
    @Override
    public EngineMeta getDocumentMeta(EngineDocument handle) {
        Document document = muDoc(handle).document;
        return new EngineMeta(
                meta(document, "Title"),
                meta(document, "Author"),
                meta(document, "Subject"),
                meta(document, "Keywords"),
                meta(document, "Creator"),
                meta(document, "Producer"),
                meta(document, "CreationDate"),
                meta(document, "ModDate"));
    }

    private String meta(Document document, String key) {
        try {
            return document.getMetaData(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * MuPDF returns the table of contents as an {@link Outline} tree whose entries carry only
     * a title and a destination URI — no page number, which is what
     * {@link EngineBookmark#getPageIdx()} needs. Each entry's destination is resolved
     * separately to recover the page. An entry whose destination does not resolve is reported
     * as page 0 rather than dropped, so the entry stays reachable in the tree.
     */
    @Override
    public List<EngineBookmark> getTableOfContents(EngineDocument handle) {
        Outline[] outlines;
        try {
            outlines = muDoc(handle).document.loadOutline();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        return convertOutlines(handle, outlines);
    }

    private List<EngineBookmark> convertOutlines(EngineDocument handle, Outline[] outlines) {
        List<EngineBookmark> result = new ArrayList<>();
        if (outlines == null) {
            return result;
        }
        for (Outline outline : outlines) {
            List<EngineBookmark> children = convertOutlines(handle, outline.down);
            result.add(new EngineBookmark(outline.title, resolvePageIndex(handle, outline), children));
        }
        return result;
    }

    private long resolvePageIndex(EngineDocument handle, Outline outline) {
        try {
            LinkDestination destination = muDoc(handle).document.resolveLinkDestination(outline);
            return destination == null ? 0 : flatPageIndex(handle, destination);
        } catch (Throwable t) {
            return 0;
        }
    }

    private long resolvePageIndex(EngineDocument handle, Link link) {
        try {
            LinkDestination destination = muDoc(handle).document.resolveLinkDestination(link);
            return destination == null ? -1 : flatPageIndex(handle, destination);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * MuPDF addresses pages as (chapter, page-within-chapter) while everything above this
     * interface uses a single flat index, so the chapter's starting offset is added.
     */
    private long flatPageIndex(EngineDocument handle, LinkDestination destination) {
        Document document = muDoc(handle).document;
        long offset = 0;
        for (int chapter = 0; chapter < destination.chapter; chapter++) {
            offset += document.countPages(chapter);
        }
        return offset + destination.page;
    }

    @Override
    public List<EngineLink> getPageLinks(EngineDocument handle, int pageIndex) {
        Link[] links;
        try {
            links = page(handle, pageIndex).getLinks();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        List<EngineLink> result = new ArrayList<>();
        if (links == null) {
            return result;
        }
        for (Link link : links) {
            com.artifex.mupdf.fitz.Rect bounds = link.getBounds();
            RectF mapped = new RectF(bounds.x0, bounds.y0, bounds.x1, bounds.y1);
            long destination = resolvePageIndex(handle, link);
            result.add(new EngineLink(mapped, destination < 0 ? null : (int) destination, link.getURI()));
        }
        return result;
    }

    /**
     * The caller describes the viewport in device pixels ({@code startX}/{@code startY} and
     * {@code sizeX}/{@code sizeY} are the page's offset and scaled size on screen) and passes a
     * rectangle in page points, so the mapping is "scale the page to the viewport, then offset
     * by the page's position in it".
     */
    @Override
    public RectF mapRectToDevice(EngineDocument handle, int pageIndex, int startX, int startY,
                                 int sizeX, int sizeY, RectF rect) {
        EngineSize pageSize = getPageSize(handle, pageIndex);
        if (pageSize.getWidth() <= 0 || sizeX <= 0) {
            return new RectF(rect);
        }
        float scale = sizeX / (float) pageSize.getWidth();
        Matrix ctm = new Matrix(scale, 0f, 0f, scale, startX, startY);
        com.artifex.mupdf.fitz.Rect mapped = new com.artifex.mupdf.fitz.Rect(rect.left, rect.top, rect.right, rect.bottom).transform(ctm);
        return new RectF(mapped.x0, mapped.y0, mapped.x1, mapped.y1);
    }

    @Override
    public void dispose() {
        // Context is process-wide and initialised on first use; there is nothing per-engine.
    }

    private Page page(EngineDocument handle, int pageIndex) {
        return muDoc(handle).page(pageIndex);
    }

    private MuDoc muDoc(EngineDocument handle) {
        return (MuDoc) handle.getNativeDocument();
    }

    private static final String MIME_PDF = "application/pdf";
}
