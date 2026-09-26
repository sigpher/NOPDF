package com.github.barteksc.pdfviewer.engine;

import java.io.IOException;

/**
 * Raised when a document is encrypted and the supplied password did not unlock it.
 *
 * <p>Exists so that callers above the engine boundary do not have to catch pdfium's
 * {@code PdfPasswordException}. MuPDF signals this condition by return value rather than
 * by exception, so the pdfium-specific type is translated here at the boundary instead of
 * being allowed to escape.
 */
public class PasswordRequiredException extends IOException {

    public PasswordRequiredException() {
        super("PDF is password protected");
    }
}
