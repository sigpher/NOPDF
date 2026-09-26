package com.github.barteksc.pdfviewer.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.List;

/**
 * The whole surface this viewer needs from a PDF engine.
 *
 * <p>Everything above this interface — {@code PdfFile}, {@code PDFView}, the
 * {@code DocumentSource} hierarchy, and the app module — is written against these types
 * only. No {@code com.shockwave.*} type may appear in a signature above this line; that is
 * the whole point of the boundary, so that the engine can be swapped without the renderer,
 * the gesture/zoom code, or the app having to change.
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
     * Renders {@code bounds} (in page points, relative to the page) of a page into
     * {@code bitmap}, which the caller has already sized and positioned.
     */
    void renderPageBitmap(EngineDocument document, Bitmap bitmap, int pageIndex, Rect bounds,
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
