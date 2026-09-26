package com.github.barteksc.pdfviewer.engine;

import android.graphics.RectF;

/**
 * Engine-neutral link annotation, mirroring {@code com.shockwave.pdfium.PdfDocument.Link}.
 */
public class EngineLink {

    private final RectF bounds;
    private final Integer destPageIndex;
    private final String uri;

    public EngineLink(RectF bounds, Integer destPageIndex, String uri) {
        this.bounds = bounds;
        this.destPageIndex = destPageIndex;
        this.uri = uri;
    }

    public RectF getBounds() {
        return bounds;
    }

    public Integer getDestPageIdx() {
        return destPageIndex;
    }

    public String getUri() {
        return uri;
    }
}
