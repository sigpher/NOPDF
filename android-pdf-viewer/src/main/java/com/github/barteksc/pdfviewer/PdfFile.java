/**
 * Copyright 2017 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.RectF;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.util.FitPolicy;
import com.github.barteksc.pdfviewer.util.PageSizeCalculator;
import com.github.barteksc.pdfviewer.engine.EngineBookmark;
import com.github.barteksc.pdfviewer.engine.EngineDocument;
import com.github.barteksc.pdfviewer.engine.EngineLink;
import com.github.barteksc.pdfviewer.engine.EngineMeta;
import com.github.barteksc.pdfviewer.engine.EngineSize;
import com.github.barteksc.pdfviewer.engine.EngineSizeF;
import com.github.barteksc.pdfviewer.engine.PageResidency;
import com.github.barteksc.pdfviewer.engine.PdfEngine;

import java.util.ArrayList;
import java.util.List;

class PdfFile {

    private static final Object lock = new Object();
    private EngineDocument document;
    private PdfEngine engine;
    private int pagesCount = 0;
    /** Original page sizes */
    private List<EngineSize> originalPageSizes = new ArrayList<>();
    /** Scaled page sizes */
    private List<EngineSizeF> pageSizes = new ArrayList<>();
    /**
     * Which pages the engine is currently holding open, capped at {@link #MAX_OPEN_PAGES}.
     *
     * <p>The cap is load-bearing rather than an optimisation — see {@link #MAX_OPEN_PAGES}.
     */
    private PageResidency residency;
    /** Page with maximum width */
    private EngineSize originalMaxWidthPageSize = new EngineSize(0, 0);
    /** Page with maximum height */
    private EngineSize originalMaxHeightPageSize = new EngineSize(0, 0);
    /** Scaled page with maximum height */
    private EngineSizeF maxHeightPageSize = new EngineSizeF(0, 0);
    /** Scaled page with maximum width */
    private EngineSizeF maxWidthPageSize = new EngineSizeF(0, 0);
    /** True if scrolling is vertical, else it's horizontal */
    private boolean isVertical;
    /** Fixed spacing between pages in pixels */
    private int spacingPx;
    /** Calculate spacing automatically so each page fits on it's own in the center of the view */
    private boolean autoSpacing;
    /** Calculated offsets for pages */
    private List<Float> pageOffsets = new ArrayList<>();
    /** Calculated auto spacing for pages */
    private List<Float> pageSpacing = new ArrayList<>();
    /** Calculated document length (width or height, depending on swipe mode) */
    private float documentLength = 0;
    private final FitPolicy pageFitPolicy;
    /**
     * True if every page should fit separately according to the FitPolicy,
     * else the largest page fits and other pages scale relatively
     */
    private final boolean fitEachPage;
    /**
     * The pages the user want to display in order
     * (ex: 0, 2, 2, 8, 8, 1, 1, 1)
     */
    private int[] originalUserPages;

    PdfFile(PdfEngine engine, EngineDocument document, FitPolicy pageFitPolicy, EngineSize viewSize, int[] originalUserPages,
            boolean isVertical, int spacing, boolean autoSpacing, boolean fitEachPage) {
        this.engine = engine;
        this.document = document;
        this.pageFitPolicy = pageFitPolicy;
        this.originalUserPages = originalUserPages;
        this.isVertical = isVertical;
        this.spacingPx = spacing;
        this.autoSpacing = autoSpacing;
        this.fitEachPage = fitEachPage;
        // Snapshots rather than the fields: the constructor parameters shadow them here, and
        // the library module is compiled at Java 7 source level so they cannot be captured
        // without being final. Neither is ever reassigned before dispose() anyway.
        final PdfEngine openEngine = engine;
        final EngineDocument openDocument = document;
        this.residency = new PageResidency(MAX_OPEN_PAGES, new PageResidency.Releaser() {
            @Override
            public void release(int pageIndex) {
                openEngine.closePage(openDocument, pageIndex);
            }
        });
        setup(viewSize);
    }

    private void setup(EngineSize viewSize) {
        if (originalUserPages != null) {
            pagesCount = originalUserPages.length;
        } else {
            pagesCount = engine.getPageCount(document);
        }

        for (int i = 0; i < pagesCount; i++) {
            EngineSize pageSize = engine.getPageSize(document, documentPage(i));
            if (pageSize.getWidth() > originalMaxWidthPageSize.getWidth()) {
                originalMaxWidthPageSize = pageSize;
            }
            if (pageSize.getHeight() > originalMaxHeightPageSize.getHeight()) {
                originalMaxHeightPageSize = pageSize;
            }
            originalPageSizes.add(pageSize);
        }

        recalculatePageSizes(viewSize);
    }

    /**
     * Call after view size change to recalculate page sizes, offsets and document length
     *
     * @param viewSize new size of changed view
     */
    public void recalculatePageSizes(EngineSize viewSize) {
        pageSizes.clear();
        PageSizeCalculator calculator = new PageSizeCalculator(pageFitPolicy, originalMaxWidthPageSize,
                originalMaxHeightPageSize, viewSize, fitEachPage);
        maxWidthPageSize = calculator.getOptimalMaxWidthPageSize();
        maxHeightPageSize = calculator.getOptimalMaxHeightPageSize();

        for (EngineSize size : originalPageSizes) {
            pageSizes.add(calculator.calculate(size));
        }
        if (autoSpacing) {
            prepareAutoSpacing(viewSize);
        }
        prepareDocLen();
        preparePagesOffset();
    }

    public int getPagesCount() {
        return pagesCount;
    }

    public EngineSizeF getPageSize(int pageIndex) {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return new EngineSizeF(0, 0);
        }
        return pageSizes.get(pageIndex);
    }

    public EngineSizeF getScaledPageSize(int pageIndex, float zoom) {
        EngineSizeF size = getPageSize(pageIndex);
        return new EngineSizeF(size.getWidth() * zoom, size.getHeight() * zoom);
    }

    /**
     * get page size with biggest dimension (width in vertical mode and height in horizontal mode)
     *
     * @return size of page
     */
    public EngineSizeF getMaxPageSize() {
        return isVertical ? maxWidthPageSize : maxHeightPageSize;
    }

    public float getMaxPageWidth() {
        return getMaxPageSize().getWidth();
    }

    public float getMaxPageHeight() {
        return getMaxPageSize().getHeight();
    }

    private void prepareAutoSpacing(EngineSize viewSize) {
        pageSpacing.clear();
        for (int i = 0; i < getPagesCount(); i++) {
            EngineSizeF pageSize = pageSizes.get(i);
            float spacing = Math.max(0, isVertical ? viewSize.getHeight() - pageSize.getHeight() :
                    viewSize.getWidth() - pageSize.getWidth());
            if (i < getPagesCount() - 1) {
                spacing += spacingPx;
            }
            pageSpacing.add(spacing);
        }
    }

    private void prepareDocLen() {
        float length = 0;
        for (int i = 0; i < getPagesCount(); i++) {
            EngineSizeF pageSize = pageSizes.get(i);
            length += isVertical ? pageSize.getHeight() : pageSize.getWidth();
            if (autoSpacing) {
                length += pageSpacing.get(i);
            } else if (i < getPagesCount() - 1) {
                length += spacingPx;
            }
        }
        documentLength = length;
    }

    private void preparePagesOffset() {
        pageOffsets.clear();
        float offset = 0;
        for (int i = 0; i < getPagesCount(); i++) {
            EngineSizeF pageSize = pageSizes.get(i);
            float size = isVertical ? pageSize.getHeight() : pageSize.getWidth();
            if (autoSpacing) {
                offset += pageSpacing.get(i) / 2f;
                if (i == 0) {
                    offset -= spacingPx / 2f;
                } else if (i == getPagesCount() - 1) {
                    offset += spacingPx / 2f;
                }
                pageOffsets.add(offset);
                offset += size + pageSpacing.get(i) / 2f;
            } else {
                pageOffsets.add(offset);
                offset += size + spacingPx;
            }
        }
    }

    public float getDocLen(float zoom) {
        return documentLength * zoom;
    }

    /**
     * Get the page's height if swiping vertical, or width if swiping horizontal.
     */
    public float getPageLength(int pageIndex, float zoom) {
        EngineSizeF size = getPageSize(pageIndex);
        return (isVertical ? size.getHeight() : size.getWidth()) * zoom;
    }

    public float getPageSpacing(int pageIndex, float zoom) {
        float spacing = autoSpacing ? pageSpacing.get(pageIndex) : spacingPx;
        return spacing * zoom;
    }

    /** Get primary page offset, that is Y for vertical scroll and X for horizontal scroll */
    public float getPageOffset(int pageIndex, float zoom) {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return 0;
        }
        return pageOffsets.get(pageIndex) * zoom;
    }

    /** Get secondary page offset, that is X for vertical scroll and Y for horizontal scroll */
    public float getSecondaryPageOffset(int pageIndex, float zoom) {
        EngineSizeF pageSize = getPageSize(pageIndex);
        if (isVertical) {
            float maxWidth = getMaxPageWidth();
            return zoom * (maxWidth - pageSize.getWidth()) / 2; //x
        } else {
            float maxHeight = getMaxPageHeight();
            return zoom * (maxHeight - pageSize.getHeight()) / 2; //y
        }
    }

    public int getPageAtOffset(float offset, float zoom) {
        int currentPage = 0;
        for (int i = 0; i < getPagesCount(); i++) {
            float off = pageOffsets.get(i) * zoom - getPageSpacing(i, zoom) / 2f;
            if (off >= offset) {
                break;
            }
            currentPage++;
        }
        return --currentPage >= 0 ? currentPage : 0;
    }

    /**
     * How many pages the engine may hold open at once.
     *
     * <p>Under pdfium an open page was a native handle that cost almost nothing to keep, and
     * {@link #openPage} was called once per page for the life of the document without anyone
     * noticing. MuPDF allocates a real page object per load — parsed contents, resources, the
     * lot — and freeing it is the only way to get the memory back. So keeping every page ever
     * visited meant the resident set grew with the length of the scroll, and a few hundred pages
     * of a long document was enough to exhaust the heap. Pages then failed to load with a bare
     * {@code RuntimeException} from the engine, which is what turned this into blank pages
     * rather than a crash.
     *
     * <p>Eight is far more than the viewer needs at once: a screenful is one page in vertical
     * mode, plus whatever {@code PRELOAD_OFFSET} pulls in. The margin is for re-opening
     * without re-parsing while scrolling between neighbours.
     */
    private static final int MAX_OPEN_PAGES = 8;

    public boolean openPage(int pageIndex) throws PageRenderingException {
        int docPage = documentPage(pageIndex);
        if (docPage < 0) {
            return false;
        }

        synchronized (lock) {
            if (residency.isHeld(docPage)) {
                // Already open, or already known to have failed; isHeld() refreshed its
                // recency, which is what keeps a page that is being drawn from being trimmed.
                return false;
            }

            PageRenderingException failure = null;
            try {
                engine.openPage(document, docPage);
            } catch (Exception e) {
                failure = new PageRenderingException(pageIndex, e);
            }
            // Recorded either way, and trimmed either way: a failure flag has to age out like
            // anything else, or a page that tripped once would stay blank for the rest of the
            // session. See PageResidency.
            residency.record(docPage, failure != null);
            residency.trim();

            if (failure != null) {
                throw failure;
            }
            return true;
        }
    }

    public boolean pageHasError(int pageIndex) {
        int docPage = documentPage(pageIndex);
        synchronized (lock) {
            return residency.hasFailed(docPage);
        }
    }

    public void renderPageBitmap(Bitmap bitmap, int pageIndex, RectF bounds, boolean annotationRendering) {
        int docPage = documentPage(pageIndex);
        engine.renderPageBitmap(document, bitmap, docPage, bounds, annotationRendering);
    }

    public EngineMeta getMetaData() {
        if (document == null) {
            return null;
        }
        return engine.getDocumentMeta(document);
    }

    public List<EngineBookmark> getBookmarks() {
        if (document == null) {
            return new ArrayList<>();
        }
        return engine.getTableOfContents(document);
    }

    public List<EngineLink> getPageLinks(int pageIndex) {
        int docPage = documentPage(pageIndex);
        return engine.getPageLinks(document, docPage);
    }

    public RectF mapRectToDevice(int pageIndex, int startX, int startY, int sizeX, int sizeY,
                                 RectF rect) {
        int docPage = documentPage(pageIndex);
        return engine.mapRectToDevice(document, docPage, startX, startY, sizeX, sizeY, rect);
    }

    public void dispose() {
        if (engine != null && document != null) {
            engine.closeDocument(document);
        }

        residency.clear();
        document = null;
        originalUserPages = null;
    }

    /**
     * Given the UserPage number, this method restrict it
     * to be sure it's an existing page. It takes care of
     * using the user defined pages if any.
     *
     * @param userPage A page number.
     * @return A restricted valid page number (example : -2 => 0)
     */
    public int determineValidPageNumberFrom(int userPage) {
        if (userPage <= 0) {
            return 0;
        }
        if (originalUserPages != null) {
            if (userPage >= originalUserPages.length) {
                return originalUserPages.length - 1;
            }
        } else {
            if (userPage >= getPagesCount()) {
                return getPagesCount() - 1;
            }
        }
        return userPage;
    }

    public int documentPage(int userPage) {
        int documentPage = userPage;
        if (originalUserPages != null) {
            if (userPage < 0 || userPage >= originalUserPages.length) {
                return -1;
            } else {
                documentPage = originalUserPages[userPage];
            }
        }

        if (documentPage < 0 || userPage >= getPagesCount()) {
            return -1;
        }

        return documentPage;
    }
}
