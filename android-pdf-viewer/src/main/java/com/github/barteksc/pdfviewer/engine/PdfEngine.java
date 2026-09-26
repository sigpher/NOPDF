package com.github.barteksc.pdfviewer.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.List;

/**
 * The whole surface this viewer needs from a PDF engine.
 *
 * <p>Everything above this interface — {@code PdfFile}, {@code PDFView}, the
 * {@code DocumentSource} hierarchy, and the app module — is written against these types
 * only. No engine-specific type may appear in a signature above this line; that is the whole
 * point of the boundary, so that the engine can be swapped without the renderer, the
 * gesture/zoom code, or the app having to change.
 *
 * <p>Implementations are not required to be thread-safe; callers serialise access where the
 * original pdfium-backed code did.
 */
public interface PdfEngine {

    /** Human-readable engine name, for logs and crash reports. */
    String getName();

    EngineDocument openDocument(ParcelFileDescriptor pfd, String password) throws IOException;

    EngineDocument openDocument(byte[] bytes, String password) throws IOException;

    void closeDocument(EngineDocument document);

    int getPageCount(EngineDocument document);

    /** Page size in points. */
    EngineSize getPageSize(EngineDocument document, int pageIndex);

    /**
     * Makes the page renderable. Kept separate from {@link #getPageSize} because the pdfium
     * engine requires an explicit open, and callers rely on that to bound how many pages are
     * resident at once.
     */
    void openPage(EngineDocument document, int pageIndex);

    /**
     * Releases whatever {@link #openPage} allocated for a page.
     *
     * <p>Symmetric with {@link #openPage}: a closed page can be opened again, and will simply be
     * loaded afresh. Callers use this to cap how many pages are held at once, which
     * {@link #openPage} does not do on its own — the difference in cost between engines is what
     * makes that necessary. Closing a page that was never opened must be a no-op.
     */
    void closePage(EngineDocument document, int pageIndex);

    /**
     * Renders one tile of a page into {@code bitmap}, which the caller has already sized.
     *
     * <p>{@code bounds} is <em>page-relative</em>: fractions of the page in 0..1, origin at the
     * top-left, exactly the rectangle {@code PagePart} carries and {@code PDFView.drawPart}
     * stretches the finished bitmap onto. It is deliberately not expressed in page points,
     * because the caller only knows the page's size in the scaled pixels it is laid out in —
     * converting here is what keeps the rendered tile and the slot it is drawn into describing
     * the same area. See {@link PageRegion}.
     *
     * <p>The bitmap need not have the same aspect ratio as the region; implementations map the
     * region onto it and the caller stretches the result back on draw.
     *
     * <p>{@code bitmap} must be {@link Bitmap.Config#ARGB_8888}. pdfium tolerated other
     * configs, MuPDF does not — its draw device wraps the bitmap's raw memory as an
     * {@code fz_pixmap} and rejects anything that is not 4 bytes per pixel. Callers must
     * not pick the config from a quality heuristic.
     */
    void renderPageBitmap(EngineDocument document, Bitmap bitmap, int pageIndex, RectF bounds,
                          boolean annotationRendering);

    /** May be null when the document carries no metadata. */
    EngineMeta getDocumentMeta(EngineDocument document);

    List<EngineBookmark> getTableOfContents(EngineDocument document);

    List<EngineLink> getPageLinks(EngineDocument document, int pageIndex);

    RectF mapRectToDevice(EngineDocument document, int pageIndex, int startX, int startY,
                          int sizeX, int sizeY, RectF rect);

    /**
     * Releases process-wide engine state. Called when the owning view is recycled; the engine
     * may afterwards be reused, so implementations must not assume this is final.
     */
    void dispose();
}
