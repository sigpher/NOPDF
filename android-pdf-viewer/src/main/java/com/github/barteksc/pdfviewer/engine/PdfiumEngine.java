package com.github.barteksc.pdfviewer.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.ParcelFileDescriptor;

import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfPasswordException;
import com.shockwave.pdfium.PdfiumCore;
import com.shockwave.pdfium.util.Size;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The pdfium-backed {@link PdfEngine}.
 *
 * <p>This is a pure adapter: every method forwards to {@link PdfiumCore} unchanged, and the
 * only work it does is translate pdfium's types into engine-neutral ones. Behaviour must stay
 * bit-for-bit identical to the code that called pdfium directly — if a change here alters what
 * gets rendered, it belongs in the engine, not here.
 */
class PdfiumEngine implements PdfEngine {

    private final PdfiumCore core;

    PdfiumEngine(Context context) {
        this.core = new PdfiumCore(context);
    }

    @Override
    public String getName() {
        return "pdfium";
    }

    @Override
    public EngineDocument openDocument(ParcelFileDescriptor pfd, String password) throws IOException {
        try {
            return new EngineDocument(this, core.newDocument(pfd, password));
        } catch (PdfPasswordException e) {
            throw new PasswordRequiredException();
        }
    }

    @Override
    public EngineDocument openDocument(byte[] bytes, String password) throws IOException {
        try {
            return new EngineDocument(this, core.newDocument(bytes, password));
        } catch (PdfPasswordException e) {
            throw new PasswordRequiredException();
        }
    }

    @Override
    public void closeDocument(EngineDocument document) {
        core.closeDocument(pdfDocument(document));
    }

    @Override
    public int getPageCount(EngineDocument document) {
        return core.getPageCount(pdfDocument(document));
    }

    @Override
    public EngineSize getPageSize(EngineDocument document, int pageIndex) {
        Size size = core.getPageSize(pdfDocument(document), pageIndex);
        return new EngineSize(size.getWidth(), size.getHeight());
    }

    @Override
    public void openPage(EngineDocument document, int pageIndex) {
        core.openPage(pdfDocument(document), pageIndex);
    }

    @Override
    public void renderPageBitmap(EngineDocument document, Bitmap bitmap, int pageIndex, Rect bounds,
                                 boolean annotationRendering) {
        core.renderPageBitmap(pdfDocument(document), bitmap, pageIndex,
                bounds.left, bounds.top, bounds.width(), bounds.height(), annotationRendering);
    }

    @Override
    public EngineMeta getDocumentMeta(EngineDocument document) {
        PdfDocument.Meta meta = core.getDocumentMeta(pdfDocument(document));
        if (meta == null) {
            return null;
        }
        return new EngineMeta(meta.getTitle(), meta.getAuthor(), meta.getSubject(), meta.getKeywords(),
                meta.getCreator(), meta.getProducer(), meta.getCreationDate(), meta.getModDate());
    }

    @Override
    public List<EngineBookmark> getTableOfContents(EngineDocument document) {
        List<PdfDocument.Bookmark> source = core.getTableOfContents(pdfDocument(document));
        List<EngineBookmark> result = new ArrayList<>(source.size());
        for (PdfDocument.Bookmark bookmark : source) {
            result.add(convertBookmark(bookmark));
        }
        return result;
    }

    private EngineBookmark convertBookmark(PdfDocument.Bookmark bookmark) {
        List<PdfDocument.Bookmark> sourceChildren = bookmark.getChildren();
        List<EngineBookmark> children = new ArrayList<>(sourceChildren.size());
        for (PdfDocument.Bookmark child : sourceChildren) {
            children.add(convertBookmark(child));
        }
        return new EngineBookmark(bookmark.getTitle(), bookmark.getPageIdx(), children);
    }

    @Override
    public List<EngineLink> getPageLinks(EngineDocument document, int pageIndex) {
        List<PdfDocument.Link> source = core.getPageLinks(pdfDocument(document), pageIndex);
        List<EngineLink> result = new ArrayList<>(source.size());
        for (PdfDocument.Link link : source) {
            result.add(new EngineLink(link.getBounds(), link.getDestPageIdx(), link.getUri()));
        }
        return result;
    }

    @Override
    public RectF mapRectToDevice(EngineDocument document, int pageIndex, int startX, int startY,
                                 int sizeX, int sizeY, RectF rect) {
        return core.mapRectToDevice(pdfDocument(document), pageIndex, startX, startY, sizeX, sizeY, 0, rect);
    }

    @Override
    public void dispose() {
        // PdfiumCore holds no process-wide state that needs tearing down.
    }

    private PdfDocument pdfDocument(EngineDocument document) {
        return (PdfDocument) document.getNativeDocument();
    }
}
