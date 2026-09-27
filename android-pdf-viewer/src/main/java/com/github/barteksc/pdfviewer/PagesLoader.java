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

import android.graphics.RectF;

import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.Diag;
import com.github.barteksc.pdfviewer.util.MathUtils;
import com.github.barteksc.pdfviewer.util.RenderSchedule;
import com.github.barteksc.pdfviewer.util.RenderSchedule.Request;
import com.github.barteksc.pdfviewer.util.Util;
import com.github.barteksc.pdfviewer.engine.EngineSizeF;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.PRELOAD_OFFSET;

class PagesLoader {

    private PDFView pdfView;
    /**
     * 只用来给 {@link com.github.barteksc.pdfviewer.model.PagePart#cacheOrder} 盖一个次序戳，
     * **不再决定淘汰顺序**——0.5.6 起淘汰顺序由 {@code util.PartCache} 的插入序决定，原因见
     * 那里的类注释。留着它是为了排查问题时能看出一个条目是第几个被请求的。
     */
    private int cacheOrder;
    private float xOffset;
    private float yOffset;
    private float pageRelativePartWidth;
    private float pageRelativePartHeight;
    private float partRenderWidth;
    private float partRenderHeight;
    private final RectF thumbnailRect = new RectF(0, 0, 1, 1);
    private final int preloadOffset;

    private class Holder {
        int row;
        int col;

        @Override
        public String toString() {
            return "Holder{" +
                    "row=" + row +
                    ", col=" + col +
                    '}';
        }
    }

    private class RenderRange {
        int page;
        GridSize gridSize;
        Holder leftTop;
        Holder rightBottom;

        RenderRange() {
            this.page = 0;
            this.gridSize = new GridSize();
            this.leftTop = new Holder();
            this.rightBottom = new Holder();
        }

        @Override
        public String toString() {
            return "RenderRange{" +
                    "page=" + page +
                    ", gridSize=" + gridSize +
                    ", leftTop=" + leftTop +
                    ", rightBottom=" + rightBottom +
                    '}';
        }
    }

    private class GridSize {
        int rows;
        int cols;

        @Override
        public String toString() {
            return "GridSize{" +
                    "rows=" + rows +
                    ", cols=" + cols +
                    '}';
        }
    }

    PagesLoader(PDFView pdfView) {
        this.pdfView = pdfView;
        this.preloadOffset = Util.getDP(pdfView.getContext(), PRELOAD_OFFSET);
    }

    private void getPageColsRows(GridSize grid, int pageIndex) {
        EngineSizeF size = pdfView.pdfFile.getPageSize(pageIndex);
        float ratioX = 1f / size.getWidth();
        float ratioY = 1f / size.getHeight();
        final float partHeight = (Constants.PART_SIZE * ratioY) / pdfView.getZoom();
        final float partWidth = (Constants.PART_SIZE * ratioX) / pdfView.getZoom();
        grid.rows = MathUtils.ceil(1f / partHeight);
        grid.cols = MathUtils.ceil(1f / partWidth);
        // 诊断：rows/cols 为 0 会让 calculatePartSize 得到 Infinity/0，随后 collectCells 里
        // 「renderWidth <= 0」把每一格都跳过 —— 那一页一个任务都不会排进去，表现就是整屏空白。
        // 正常页面的 size 不会是 0，所以这里若触发说明页尺寸在某次取用中算错了，必须看见。
        if (grid.rows <= 0 || grid.cols <= 0 || size.getWidth() <= 0 || size.getHeight() <= 0) {
            Diag.log("GRID-ANOMALY page=" + pageIndex
                    + " size=" + Diag.f(size.getWidth()) + "x" + Diag.f(size.getHeight())
                    + " rows=" + grid.rows + " cols=" + grid.cols
                    + " zoom=" + Diag.f(pdfView.getZoom()));
        }
    }

    private void calculatePartSize(GridSize grid) {
        pageRelativePartWidth = 1f / (float) grid.cols;
        pageRelativePartHeight = 1f / (float) grid.rows;
        partRenderWidth = Constants.PART_SIZE / pageRelativePartWidth;
        partRenderHeight = Constants.PART_SIZE / pageRelativePartHeight;
    }


    /**
     * calculate the render range of each page
     */
    private List<RenderRange> getRenderRangeList(float firstXOffset, float firstYOffset, float lastXOffset, float lastYOffset) {

        float fixedFirstXOffset = -MathUtils.max(firstXOffset, 0);
        float fixedFirstYOffset = -MathUtils.max(firstYOffset, 0);

        float fixedLastXOffset = -MathUtils.max(lastXOffset, 0);
        float fixedLastYOffset = -MathUtils.max(lastYOffset, 0);

        float offsetFirst = pdfView.isSwipeVertical() ? fixedFirstYOffset : fixedFirstXOffset;
        float offsetLast = pdfView.isSwipeVertical() ? fixedLastYOffset : fixedLastXOffset;

        int firstPage = pdfView.pdfFile.getPageAtOffset(offsetFirst, pdfView.getZoom());
        int lastPage = pdfView.pdfFile.getPageAtOffset(offsetLast, pdfView.getZoom());
        int pageCount = lastPage - firstPage + 1;

        List<RenderRange> renderRanges = new LinkedList<>();

        for (int page = firstPage; page <= lastPage; page++) {
            RenderRange range = new RenderRange();
            range.page = page;

            float pageFirstXOffset, pageFirstYOffset, pageLastXOffset, pageLastYOffset;
            if (page == firstPage) {
                pageFirstXOffset = fixedFirstXOffset;
                pageFirstYOffset = fixedFirstYOffset;
                if (pageCount == 1) {
                    pageLastXOffset = fixedLastXOffset;
                    pageLastYOffset = fixedLastYOffset;
                } else {
                    float pageOffset = pdfView.pdfFile.getPageOffset(page, pdfView.getZoom());
                    EngineSizeF pageSize = pdfView.pdfFile.getScaledPageSize(page, pdfView.getZoom());
                    if (pdfView.isSwipeVertical()) {
                        pageLastXOffset = fixedLastXOffset;
                        pageLastYOffset = pageOffset + pageSize.getHeight();
                    } else {
                        pageLastYOffset = fixedLastYOffset;
                        pageLastXOffset = pageOffset + pageSize.getWidth();
                    }
                }
            } else if (page == lastPage) {
                float pageOffset = pdfView.pdfFile.getPageOffset(page, pdfView.getZoom());

                if (pdfView.isSwipeVertical()) {
                    pageFirstXOffset = fixedFirstXOffset;
                    pageFirstYOffset = pageOffset;
                } else {
                    pageFirstYOffset = fixedFirstYOffset;
                    pageFirstXOffset = pageOffset;
                }

                pageLastXOffset = fixedLastXOffset;
                pageLastYOffset = fixedLastYOffset;

            } else {
                float pageOffset = pdfView.pdfFile.getPageOffset(page, pdfView.getZoom());
                EngineSizeF pageSize = pdfView.pdfFile.getScaledPageSize(page, pdfView.getZoom());
                if (pdfView.isSwipeVertical()) {
                    pageFirstXOffset = fixedFirstXOffset;
                    pageFirstYOffset = pageOffset;

                    pageLastXOffset = fixedLastXOffset;
                    pageLastYOffset = pageOffset + pageSize.getHeight();
                } else {
                    pageFirstXOffset = pageOffset;
                    pageFirstYOffset = fixedFirstYOffset;

                    pageLastXOffset = pageOffset + pageSize.getWidth();
                    pageLastYOffset = fixedLastYOffset;
                }
            }

            getPageColsRows(range.gridSize, range.page); // get the page's grid size that rows and cols
            EngineSizeF scaledPageSize = pdfView.pdfFile.getScaledPageSize(range.page, pdfView.getZoom());
            float rowHeight = scaledPageSize.getHeight() / range.gridSize.rows;
            float colWidth = scaledPageSize.getWidth() / range.gridSize.cols;


            // get the page offset int the whole file
            // ---------------------------------------
            // |            |           |            |
            // |<--offset-->|   (page)  |<--offset-->|
            // |            |           |            |
            // |            |           |            |
            // ---------------------------------------
            float secondaryOffset = pdfView.pdfFile.getSecondaryPageOffset(page, pdfView.getZoom());

            // calculate the row,col of the point in the leftTop and rightBottom
            if (pdfView.isSwipeVertical()) {
                range.leftTop.row = MathUtils.floor(Math.abs(pageFirstYOffset - pdfView.pdfFile.getPageOffset(range.page, pdfView.getZoom())) / rowHeight);
                range.leftTop.col = MathUtils.floor(MathUtils.min(pageFirstXOffset - secondaryOffset, 0) / colWidth);

                range.rightBottom.row = MathUtils.ceil(Math.abs(pageLastYOffset - pdfView.pdfFile.getPageOffset(range.page, pdfView.getZoom())) / rowHeight);
                range.rightBottom.col = MathUtils.floor(MathUtils.min(pageLastXOffset - secondaryOffset, 0) / colWidth);
            } else {
                range.leftTop.col = MathUtils.floor(Math.abs(pageFirstXOffset - pdfView.pdfFile.getPageOffset(range.page, pdfView.getZoom())) / colWidth);
                range.leftTop.row = MathUtils.floor(MathUtils.min(pageFirstYOffset - secondaryOffset, 0) / rowHeight);

                range.rightBottom.col = MathUtils.floor(Math.abs(pageLastXOffset - pdfView.pdfFile.getPageOffset(range.page, pdfView.getZoom())) / colWidth);
                range.rightBottom.row = MathUtils.floor(MathUtils.min(pageLastYOffset - secondaryOffset, 0) / rowHeight);
            }

            renderRanges.add(range);
        }

        return renderRanges;
    }

    private void loadVisible() {
        float scaledPreloadOffset = preloadOffset;
        float firstXOffset = -xOffset + scaledPreloadOffset;
        float lastXOffset = -xOffset - pdfView.getWidth() - scaledPreloadOffset;
        float firstYOffset = -yOffset + scaledPreloadOffset;
        float lastYOffset = -yOffset - pdfView.getHeight() - scaledPreloadOffset;

        List<RenderRange> rangeList = getRenderRangeList(firstXOffset, firstYOffset, lastXOffset, lastYOffset);
        if (rangeList.isEmpty()) {
            // 诊断：rangeList 为空就直接 return，本轮一个任务都不排 —— 整屏因此什么都不画。
            // getRenderRangeList 的循环是「for (page = firstPage; page <= lastPage; page++)」，
            // 所以只有 firstPage > lastPage 时才会空。offsets 与 pages 一并记下来，才能判断是
            // 几何算错了还是页面范围本身就没覆盖到屏幕。
            if (Diag.due("load-norange", 1000)) {
                Diag.log("LOAD-NORANGE xOff=" + Diag.f(xOffset) + " yOff=" + Diag.f(yOffset)
                        + " zoom=" + Diag.f(pdfView.getZoom())
                        + " view=" + pdfView.getWidth() + "x" + pdfView.getHeight()
                        + " firstOff=" + Diag.f(firstYOffset) + " lastOff=" + Diag.f(lastYOffset)
                        + " pages=" + pdfView.pdfFile.getPagesCount());
            }
            return;
        }

        int currentPage = pdfView.getCurrentPage();

        // 先把这一轮真正要画的东西收集起来，**不在这里入队**——入队顺序就是优先级，
        // 而优先级是这一轮能不能画出东西的决定因素（见 RenderSchedule）。
        List<Cell> cells = new ArrayList<>();
        List<Request> requests = new ArrayList<>();

        for (RenderRange range : rangeList) {
            calculatePartSize(range.gridSize);
            collectCells(range, currentPage, cells, requests);
        }
        for (RenderRange range : rangeList) {
            collectThumbnail(range.page, currentPage, cells, requests);
        }

        // 分块预算取 CACHE_SIZE，与改动前一致；缩略图的预算另算，它不占分块的名额。
        List<Request> ordered = RenderSchedule.order(requests, CACHE_SIZE, THUMBNAILS_CACHE_SIZE);
        for (int i = 0; i < ordered.size(); i++) {
            Cell cell = cells.get(ordered.get(i).index);
            cacheOrder++;
            pdfView.renderingHandler.addRenderingTask(cell.page, cell.renderWidth, cell.renderHeight,
                    cell.bounds, cell.thumbnail, cacheOrder, pdfView.isBestQuality(),
                    pdfView.isAnnotationRendering());
        }

        // 诊断：本轮「收集到多少」与「实际排进队列多少」必须同时可见。两者差得远，说明预算或排序
        // 在饿死真正要画的东西；collected=0 则说明缓存认为屏内每一格都已经在里面了。
        if (Diag.due("load", 400)) {
            int thumbs = 0;
            for (int i = 0; i < requests.size(); i++) {
                if (requests.get(i).thumbnail) {
                    thumbs++;
                }
            }
            Diag.log("LOAD ranges=" + rangeList.size()
                    + " page=" + rangeList.get(0).page + ".." + rangeList.get(rangeList.size() - 1).page
                    + " collected=" + requests.size() + " (tiles=" + (requests.size() - thumbs)
                    + " thumbs=" + thumbs + ")"
                    + " enqueued=" + ordered.size()
                    + " budget=" + CACHE_SIZE + "/" + THUMBNAILS_CACHE_SIZE
                    + " zoom=" + Diag.f(pdfView.getZoom())
                    + " yOff=" + Diag.f(yOffset));
        }
    }

    /**
     * 把这一页还缺的分块收进 {@code cells}/{@code requests}，不入队。
     *
     * <p>已经在缓存里的分块只更新一下 LRU 时戳就跳过。改动前这里是「访问一格就算一格」，
     * 于是**缓存里已有的格子也会把预算吃光**，一整轮可能一个任务都没排进去——滑过去再滑回来
     * 的那一页恰好是缓存里格子最多的那种。现在预算只花在真正要入队的分块上。
     */
    private void collectCells(RenderRange range, int currentPage, List<Cell> cells, List<Request> requests) {
        for (int row = range.leftTop.row; row <= range.rightBottom.row; row++) {
            for (int col = range.leftTop.col; col <= range.rightBottom.col; col++) {
                float relX = pageRelativePartWidth * col;
                float relY = pageRelativePartHeight * row;
                float relWidth = pageRelativePartWidth;
                float relHeight = pageRelativePartHeight;

                float renderWidth = partRenderWidth;
                float renderHeight = partRenderHeight;
                if (relX + relWidth > 1) {
                    relWidth = 1 - relX;
                }
                if (relY + relHeight > 1) {
                    relHeight = 1 - relY;
                }
                renderWidth *= relWidth;
                renderHeight *= relHeight;
                if (renderWidth <= 0 || renderHeight <= 0) {
                    continue;
                }

                RectF pageRelativeBounds = new RectF(relX, relY, relX + relWidth, relY + relHeight);
                if (!pdfView.cacheManager.upPartIfContained(range.page, pageRelativeBounds)) {
                    cells.add(new Cell(range.page, renderWidth, renderHeight, pageRelativeBounds, false));
                    requests.add(new Request(cells.size() - 1, range.page,
                            distanceTo(range.page, currentPage), false));
                }
                cacheOrder++;
            }
        }
    }

    private void collectThumbnail(int page, int currentPage, List<Cell> cells, List<Request> requests) {
        if (pdfView.cacheManager.containsThumbnail(page, thumbnailRect)) {
            return;
        }
        EngineSizeF pageSize = pdfView.pdfFile.getPageSize(page);
        cells.add(new Cell(page, pageSize.getWidth() * Constants.THUMBNAIL_RATIO,
                pageSize.getHeight() * Constants.THUMBNAIL_RATIO, thumbnailRect, true));
        requests.add(new Request(cells.size() - 1, page, distanceTo(page, currentPage), true));
    }

    private static int distanceTo(int page, int currentPage) {
        return Math.abs(page - currentPage);
    }

    /** 一条待渲染的请求，连同它的渲染参数。 */
    private static final class Cell {

        final int page;
        final float renderWidth;
        final float renderHeight;
        final RectF bounds;
        final boolean thumbnail;

        Cell(int page, float renderWidth, float renderHeight, RectF bounds, boolean thumbnail) {
            this.page = page;
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
            this.bounds = bounds;
            this.thumbnail = thumbnail;
        }
    }

    void loadPages() {
        cacheOrder = 1;
        xOffset = -MathUtils.max(pdfView.getCurrentXOffset(), 0);
        yOffset = -MathUtils.max(pdfView.getCurrentYOffset(), 0);

        loadVisible();
    }
}
