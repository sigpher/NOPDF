package com.github.barteksc.pdfviewer.engine;

import android.content.Context;

/**
 * Creates the {@link PdfEngine} the viewer will use.
 *
 * <p>This is the single place that decides which engine is in play. Swapping engines means
 * changing the body of {@link #create(Context)} and nothing else; every other class in the
 * viewer and in the app module is written against {@link PdfEngine} and its neutral types.
 */
public final class PdfEngines {

    private PdfEngines() {
    }

    public static PdfEngine create(Context context) {
        return new PdfiumEngine(context.getApplicationContext());
    }
}
