package com.sigpher.nopdf.preview

import com.github.barteksc.pdfviewer.engine.EngineBookmark

/**
 * @author Aaron aaronzzxup@gmail.com
 */
interface IContentFragInterface {
    fun update(collection: MutableCollection<EngineBookmark>)
}