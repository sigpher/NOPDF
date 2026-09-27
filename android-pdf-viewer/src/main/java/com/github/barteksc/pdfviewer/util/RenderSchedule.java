/**
 * Copyright 2016 Bartosz Schiller
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
package com.github.barteksc.pdfviewer.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 决定一轮 {@code PDFView.loadPages()} 里各项渲染请求的入队先后顺序。
 *
 * <p>渲染请求的消费者只有一个线程（{@code RenderingHandler} 所在的 HandlerThread），
 * 而生产者跑得非常高频：{@code loadPages()} 在**每一个** touch 事件
 * （{@code DragPinchManager.onScroll}）和滑动动画的**每一帧**
 * （{@code AnimationManager.computeFling}）上都会跑一次。一帧只有几毫秒，而画一格要重跑整页
 * 内容——换 MuPDF 之后不再是 pdfium 那种便宜的单块渲染——比一帧长得多。于是「先排什么」
 * 直接决定屏幕上有没有内容：排在队首的任务每轮都会被重新排到队首，排在队尾的任务一轮都轮不到。
 *
 * <p>三条规则：
 *
 * <ol>
 *   <li><b>分块优先于缩略图</b>。缩略图只是首帧的占位，画在分块**下面**；把它排在分块前面，
 *       等于让渲染线程每轮都先重画一遍没人看的缩略图。</li>
 *   <li><b>只有「这一轮真要画分块」的页才排缩略图</b>。分块一旦进缓存就把该页的缩略图完全盖住
 *       （{@code PDFView.onDraw} 先画缩略图、再画分块），这时再画那张缩略图是纯浪费。
 *       这条是「快速滑动后整页空白」的直接成因，见 {@code PagesLoader} 与 AGENTS.md。</li>
 *   <li><b>预算只花在分块上</b>。缩略图不能把真实内容挤出预算。</li>
 * </ol>
 *
 * <p>纯 JVM，不碰任何 Android 类型，所以可以单测（见 {@code RenderScheduleTest}）。
 */
public final class RenderSchedule {

    /** 不限制数量。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private RenderSchedule() {
    }

    /**
     * 一条待渲染的请求。
     *
     * <p>刻意只带基本类型：调用方自己保存渲染参数（宽高、矩形），用 {@link #index} 指回来。
     * 这样本类不依赖 {@code RectF} 之类的 Android 类型，测试里可以直接构造。
     */
    public static final class Request {

        /** 调用方列表里这条请求的位置，用来取回真正的渲染参数。 */
        public final int index;

        /** 所属页号。 */
        public final int page;

        /** 离视口有多远，0 表示离视口最近的一页。同距离的按 {@link #index} 先后。 */
        public final int distance;

        /** 是缩略图还是分块。 */
        public final boolean thumbnail;

        public Request(int index, int page, int distance, boolean thumbnail) {
            this.index = index;
            this.page = page;
            this.distance = distance;
            this.thumbnail = thumbnail;
        }

        @Override
        public String toString() {
            return (thumbnail ? "缩略图" : "分块") + "[page=" + page + ", distance=" + distance
                    + ", index=" + index + "]";
        }
    }

    /** 离视口近的先画；距离相同则保持传入次序。 */
    private static final Comparator<Request> NEAREST_FIRST = new Comparator<Request>() {
        @Override
        public int compare(Request first, Request second) {
            if (first.distance != second.distance) {
                return first.distance < second.distance ? -1 : 1;
            }
            if (first.index == second.index) {
                return 0;
            }
            return first.index < second.index ? -1 : 1;
        }
    };

    /**
     * 挑出本轮应当入队的请求，并给出入队顺序。
     *
     * @param requests 这一轮需要渲染的请求，次序无所谓（内部会重排）
     * @param cellBudget 本轮最多入队的**分块**数，缩略图不计入
     * @param thumbnailBudget 本轮最多入队的**缩略图**数
     * @return 应当入队的请求（就是传进来的那些对象本身），列表顺序即渲染顺序
     */
    public static List<Request> order(List<Request> requests, int cellBudget, int thumbnailBudget) {
        List<Request> cells = new ArrayList<>();
        List<Request> thumbnails = new ArrayList<>();

        // 用**入预算之前**的集合判断：某页的分块若被预算截断（只排了一部分），它的缩略图
        // 仍然有用——没排上的那几格画出来之前，缩略图正是要顶上来的东西。
        Set<Integer> pagesWithCells = new HashSet<>();

        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            if (request.thumbnail) {
                thumbnails.add(request);
            } else {
                cells.add(request);
                pagesWithCells.add(request.page);
            }
        }

        List<Request> useful = new ArrayList<>();
        for (int i = 0; i < thumbnails.size(); i++) {
            Request request = thumbnails.get(i);
            if (pagesWithCells.contains(request.page)) {
                useful.add(request);
            }
        }

        List<Request> ordered = new ArrayList<>(cells.size() + useful.size());
        Collections.sort(cells, NEAREST_FIRST);
        addFirstN(ordered, cells, cellBudget);
        Collections.sort(useful, NEAREST_FIRST);
        addFirstN(ordered, useful, thumbnailBudget);
        return ordered;
    }

    private static void addFirstN(List<Request> out, List<Request> sorted, int budget) {
        int n = Math.min(budget, sorted.size());
        for (int i = 0; i < n; i++) {
            out.add(sorted.get(i));
        }
    }
}
