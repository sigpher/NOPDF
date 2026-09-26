package com.github.barteksc.pdfviewer.engine;

/**
 * Engine-neutral handle for an opened document.
 *
 * <p>The payload is opaque to every caller above the engine boundary: it is whatever the
 * concrete {@link PdfEngine} produced (currently a {@code com.shockwave.pdfium.PdfDocument},
 * later a {@code com.artifex.mupdf.fitz.Document}). Callers must only pass it back to the
 * same engine instance that created it.
 */
public final class EngineDocument {

    private final PdfEngine engine;
    private final Object nativeDocument;

    EngineDocument(PdfEngine engine, Object nativeDocument) {
        this.engine = engine;
        this.nativeDocument = nativeDocument;
    }

    /** The engine that owns this document, and the only one allowed to operate on it. */
    public PdfEngine getEngine() {
        return engine;
    }

    Object getNativeDocument() {
        return nativeDocument;
    }
}
