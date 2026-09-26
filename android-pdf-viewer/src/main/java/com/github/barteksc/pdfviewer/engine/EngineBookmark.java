package com.github.barteksc.pdfviewer.engine;

import java.util.Collections;
import java.util.List;

/**
 * Engine-neutral table-of-contents entry, mirroring {@code com.shockwave.pdfium.PdfDocument.Bookmark}.
 *
 * <p>Deliberately a snapshot rather than a view onto a native object: the tree is read once,
 * at load time, and must stay valid after the underlying page/document handle is released.
 */
public class EngineBookmark {

    private final String title;
    private final long pageIndex;
    private final List<EngineBookmark> children;

    public EngineBookmark(String title, long pageIndex, List<EngineBookmark> children) {
        this.title = title;
        this.pageIndex = pageIndex;
        this.children = children == null ? Collections.<EngineBookmark>emptyList() : children;
    }

    public String getTitle() {
        return title;
    }

    public long getPageIdx() {
        return pageIndex;
    }

    public List<EngineBookmark> getChildren() {
        return children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }
}
