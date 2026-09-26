package com.sigpher.nopdf.common.event;

import com.github.barteksc.pdfviewer.engine.EngineBookmark;

import java.util.List;

/**
 * @author Aaron aaronzzxup@gmail.com
 */
public class ContentEvent {

    private List<EngineBookmark> bookmarkList;

    public ContentEvent(List<EngineBookmark> bookmarkList) {
        this.bookmarkList = bookmarkList;
    }

    public List<EngineBookmark> getBookmarkList() {
        return bookmarkList;
    }
}
