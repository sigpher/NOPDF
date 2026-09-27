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
package com.github.barteksc.pdfviewer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.github.barteksc.pdfviewer.exception.PageRenderingException;
import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.util.Diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Handler} that will process incoming {@link RenderingTask} messages
 * and alert {@link PDFView#onBitmapRendered(PagePart)} when the portion of the
 * PDF is ready to render.
 */
class RenderingHandler extends Handler {
    /**
     * {@link Message#what} kind of message this handler processes.
     */
    static final int MSG_RENDER_TASK = 1;

    private static final String TAG = RenderingHandler.class.getName();

    private PDFView pdfView;

    private boolean running = false;

    /**
     * 已经排进队列、但还没画完的任务，按入队顺序。
     *
     * <p>这张表取代了原先「{@code PDFView.loadPages()} 一跑就把整条队列清空」的做法。
     * 整体清空在快速滑动下是致命的，而这不是猜测：{@code loadPages()} 在**每一个** touch 事件
     * （{@code DragPinchManager.onScroll}）和滑动动画的**每一帧**
     * （{@code AnimationManager.computeFling}）上都会跑一次，一帧只有几毫秒，画一格却要重跑
     * 整页内容（换 MuPDF 之后不再是 pdfium 那种便宜的单块渲染）。于是渲染线程刚画完一格，
     * 队列就被下一次调用整个清掉，它永远在原地重做队首那几个任务——滑动中整页空白，而只有
     * 重新载入文档（切换主题按钮走的就是 initPdf → load）才会把一切重画一遍。
     *
     * <p>改为：入队时去重（同一格不排两次），每轮结束时只丢掉**这一轮不再需要**的任务
     * （{@link #dropStaleTasks()}）。仍然需要的任务留在队列里、保持原有次序，渲染线程的
     * 进度就不再被反复清零。丢弃这一步不是可选优化：不丢的话，滑过一千页就会在队列里
     * 积压几万个永远轮不到、也永远不会释放的消息。
     *
     * <p>主线程（{@code loadPages}）与渲染线程（{@code handleMessage}）都要动这张表，所以
     * 每次访问都在 {@link #queueLock} 内。加锁次序固定为 queueLock → MessageQueue 的内部锁；
     * 渲染线程是在 {@code handleMessage} 里取 queueLock，那一刻并不持有后者，因此不会死锁。
     */
    private final Map<TileKey, RenderingTask> pending = new LinkedHashMap<>();

    private final Object queueLock = new Object();

    /**
     * 每 {@link #beginPass()} 加一。带更小 generation 的排队任务就是「上一轮剩下的」，
     * 由 {@link #dropStaleTasks()} 清掉。
     */
    private int generation;

    /** 诊断用：画完的分块计数，由 {@link #renderedCount()} 取走。 */
    private int rendered;

    /** 诊断用：{@code proceed()} 返回 null 的次数与原因分类。 */
    private int nullByPageError;
    private int nullByZeroSize;
    private int nullByRunning;

    RenderingHandler(Looper looper, PDFView pdfView) {
        super(looper);
        this.pdfView = pdfView;
    }

    /** 划出新一轮请求的分界。见 {@link #dropStaleTasks()}。 */
    void beginPass() {
        synchronized (queueLock) {
            generation++;
        }
    }

    void addRenderingTask(int page, float width, float height, RectF bounds, boolean thumbnail, int cacheOrder, boolean bestQuality, boolean annotationRendering) {
        TileKey key = new TileKey(page, bounds, thumbnail);
        synchronized (queueLock) {
            RenderingTask existing = pending.get(key);
            if (existing != null) {
                // 已经在队列里、或者正在画：重复入队只会让同一格被画两遍，并让队列越排越长。
                // 但**必须**把它的轮次更新到当前轮——否则本轮仍然需要的任务会被
                // dropStaleTasks 当成上一轮的残留清掉，那一格就再也没人画了。
                existing.generation = generation;
                return;
            }
            RenderingTask task = new RenderingTask(key, width, height, bounds, page, thumbnail, cacheOrder, bestQuality, annotationRendering, generation);
            pending.put(key, task);
            // sendMessage 不阻塞，故可以放在锁内；见 pending 字段注释里关于加锁次序的说明。
            sendMessage(obtainMessage(MSG_RENDER_TASK, task));
        }
    }

    /**
     * 丢掉「本轮不再需要、且还没开始画」的任务。仍然需要的任务留在队列里、保持原有次序。
     *
     * <p>由 {@code PDFView.loadPages()} 在收集完本轮请求之后立刻调用。
     */
    void dropStaleTasks() {
        List<RenderingTask> stale = new ArrayList<>();
        int current;
        synchronized (queueLock) {
            current = generation;
            for (Map.Entry<TileKey, RenderingTask> entry : pending.entrySet()) {
                RenderingTask task = entry.getValue();
                // 正在画的那一条不用管：它画完照样进缓存，而它的 pending 条目由 handleMessage
                // 清掉。Handler.removeMessages(int, Object) 返回 void，没法从返回值上看出消息
                // 是否还在队列里，所以这里自己记一个 inFlight。
                if (task.generation < current && !task.inFlight) {
                    stale.add(task);
                }
            }
            for (int i = 0; i < stale.size(); i++) {
                pending.remove(stale.get(i).key);
            }
        }

        for (int i = 0; i < stale.size(); i++) {
            removeMessages(MSG_RENDER_TASK, stale.get(i));
        }

        // 诊断：一轮丢掉多少个任务。正常滑动时丢掉的多是「已经滑过去了」的，那是设计意图；但如果
        // 丢掉的量与本轮排进去的量同阶甚至更大，就要怀疑「本轮仍然需要的任务被误判成上一轮残留」
        // ——那会让那一格再也没人画（dropStaleTasks 判的是 generation < current）。
        if (stale.size() > 0 && Diag.due("drop", 400)) {
            Diag.log("DROP stale=" + stale.size() + " gen=" + current
                    + " stillQueued=" + pendingCount());
        }
    }

    /** 全部丢弃。{@code PDFView.recycle()} 用。 */
    void clearQueue() {
        removeMessages(MSG_RENDER_TASK);
        synchronized (queueLock) {
            pending.clear();
            generation++;
        }
    }

    /** 诊断用：排队中（含正在画的）任务数。 */
    int pendingCount() {
        synchronized (queueLock) {
            return pending.size();
        }
    }

    /**
     * 诊断用：自上次调用以来画完的分块数。
     *
     * <p>取走即清零，所以调用者能算出「这一轮排进去的任务到底有没有被画出来」。渲染线程一旦死掉，
     * 这个数就会停在非零值上不再增长——这是把「渲染线程死了」和「没人排任务」区分开的关键。
     */
    int renderedCount() {
        synchronized (queueLock) {
            int n = rendered;
            rendered = 0;
            return n;
        }
    }

    /**
     * 诊断用：{@code proceed()} 返回 null 的累计次数，按原因分类。
     *
     * <p>「排了任务但一张都没画出来」和「压根没排任务」的区别全在这里：前者会让这三个计数上涨。
     */
    String nullSummary() {
        synchronized (queueLock) {
            return "nulls(zero=" + nullByZeroSize + ",err=" + nullByPageError
                    + ",stopped=" + nullByRunning + ")";
        }
    }

    /**
     * 标识「要画哪一格」。与 {@code PagePart} 的区别有二：{@code PagePart} 没重写
     * {@code hashCode}，拿它当 map 的键会让去重彻底失效（每 new 一个都算不同的键）；而且
     * {@code PagePart.equals} 不看 thumbnail——横滑整页翻页时缩略图尺寸就是整页大小，恰好与
     * 「整页一块」的分块重合，两者混为一谈会把 0.3 缩略图拉伸铺满整页。
     */
    private static final class TileKey {

        private final int page;
        private final boolean thumbnail;
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;
        private final int hash;

        TileKey(int page, RectF bounds, boolean thumbnail) {
            this.page = page;
            this.thumbnail = thumbnail;
            this.left = bounds.left;
            this.top = bounds.top;
            this.right = bounds.right;
            this.bottom = bounds.bottom;

            int h = page;
            h = 31 * h + (thumbnail ? 1231 : 1237);
            h = 31 * h + Float.floatToIntBits(left);
            h = 31 * h + Float.floatToIntBits(top);
            h = 31 * h + Float.floatToIntBits(right);
            h = 31 * h + Float.floatToIntBits(bottom);
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TileKey)) {
                return false;
            }
            TileKey other = (TileKey) obj;
            return page == other.page && thumbnail == other.thumbnail
                    && left == other.left && top == other.top
                    && right == other.right && bottom == other.bottom;
        }
    }

    @Override
    public void handleMessage(Message message) {
        final RenderingTask task = (RenderingTask) message.obj;
        synchronized (queueLock) {
            task.inFlight = true;
        }
        try {
            final PagePart part = proceed(task);
            if (part != null) {
                synchronized (queueLock) {
                    rendered++;
                }
                if (running) {
                    pdfView.post(new Runnable() {
                        @Override
                        public void run() {
                            pdfView.onBitmapRendered(part);
                        }
                    });
                } else {
                    part.getRenderedBitmap().recycle();
                    synchronized (queueLock) {
                        nullByRunning++;
                    }
                }
            }
        } catch (final PageRenderingException ex) {
            pdfView.post(new Runnable() {
                @Override
                public void run() {
                    pdfView.onPageError(ex);
                }
            });
        } catch (final Throwable t) {
            // 渲染引擎抛出的**任何**其它异常都曾直接逃出 handleMessage，成为渲染线程上的
            // 未捕获异常，进而杀掉整个进程（Android 的默认未捕获异常处理器会终止进程）。
            // 换 MuPDF 后这不是假设：AndroidDrawDevice 对非 RGBA_8888 的 Bitmap 抛的是
            // 裸 RuntimeException，不是 PageRenderingException。
            //
            // 渲染失败属于可报告、可跳过的单页故障，不该拖垮进程。统一包成
            // PageRenderingException 走 onPageError —— 也就是 PreviewActivity 里
            // UiManager.showShort(...) 那条提示，至少用户看得见发生了什么。
            Log.e(TAG, "Rendering failed", t);
            pdfView.post(new Runnable() {
                @Override
                public void run() {
                    pdfView.onPageError(new PageRenderingException(task.page, t));
                }
            });
        } finally {
            // 无论画成、画失败还是抛异常，这一格都不再算「排队中」，下一轮可以重新排它。
            // 放在画完之后而不是取出来的时候，是为了不让同一格在画的同时被再排一遍（会画两遍）。
            synchronized (queueLock) {
                pending.remove(task.key);
            }
        }
    }

    private PagePart proceed(RenderingTask renderingTask) throws PageRenderingException {
        PdfFile pdfFile = pdfView.pdfFile;
        pdfFile.openPage(renderingTask.page);

        int w = Math.round(renderingTask.width);
        int h = Math.round(renderingTask.height);

        // 诊断：返回 null 的三个原因必须分开记。混在一起 return 的话，「这一格没画出来」在日志上
        // 和「这一格被请求了但画不出来」长得一样，而这两者的修法完全不同。
        //   zeroSize  -> 请求侧的尺寸算错了（看 PagesLoader 的 GRID-ANOMALY）
        //   pageError -> 引擎开页失败（看 PdfFile 的 OPEN-FAIL）
        if (w == 0 || h == 0) {
            synchronized (queueLock) {
                nullByZeroSize++;
            }
            if (Diag.due("proceed-zero", 1000)) {
                Diag.log("PROCEED-NULL reason=zeroSize page=" + renderingTask.page
                        + " req=" + Diag.f(renderingTask.width) + "x" + Diag.f(renderingTask.height)
                        + " thumb=" + renderingTask.thumbnail);
            }
            return null;
        }
        if (pdfFile.pageHasError(renderingTask.page)) {
            synchronized (queueLock) {
                nullByPageError++;
            }
            if (Diag.due("proceed-err", 1000)) {
                Diag.log("PROCEED-NULL reason=pageHasError page=" + renderingTask.page
                        + " thumb=" + renderingTask.thumbnail);
            }
            return null;
        }

        Bitmap render;
        try {
            // 必须恒为 ARGB_8888：bestQuality 只用来选清晰度，**不能**用来选 Bitmap.Config。
            //
            // pdfium 直接按调用方给的 Bitmap 写入，RGB_565 也能画，所以旧代码在这里按
            // bestQuality 退回 RGB_565 省一半内存（低 zoom 的 part 缓存按张数计，120 张 ×
            // 256px 见 Constants.Cache）。换 MuPDF 后这条路走不通了——
            // AndroidDrawDevice 的 JNI 绑定直接拿 Bitmap 的裸内存当 fz_pixmap 用，并硬性
            // 要求 4 字节/像素：
            //     if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
            //         jni_throw_run(env, "new DrawDevice failed as bitmap format is not RGBA_8888");
            //     if (info.stride != info.width * 4)
            //         jni_throw_run(env, "new DrawDevice failed as bitmap width != stride");
            // 传 RGB_565 进去会抛 RuntimeException。而本类 handleMessage 只捕获
            // PageRenderingException，于是这个异常逃到渲染线程的 Looper 里成为未捕获异常，
            // **整个进程被杀**——表现就是「一打开 PDF 就闪退」。
            //
            // 内存并不因此回退：Constants.Cache 的容量估算（CACHE_SIZE 120 × PART_SIZE 256
            // × 4B ≈ 30MB，见 AGENTS.md）本来就是按 4 字节/像素算的。
            render = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Cannot create bitmap", e);
            return null;
        }
        // The task's bounds are page-relative (fractions of the page) and are handed to the
        // engine unchanged, for two reasons.
        //
        // They used to be run through a Matrix here that tried to turn the fractions into a
        // page-point rectangle, but it had no page size to work from — only the tile's own
        // pixel dimensions — so the rectangle it produced came out roughly the size of the whole
        // page, and with a negative origin for every tile but the first. MuPDF duly painted
        // (almost) the entire page into each tile, and PDFView.drawPart then stretched each of
        // those onto its own 1/cols x 1/rows slot: the page came out as a grid of many repeated
        // miniature pages. The engine knows the page's point size, so the conversion belongs
        // there — see PageRegion.
        //
        // Keeping one rectangle for both ends also makes the invariant checkable: a tile's bitmap
        // covers exactly the area its PagePart.pageRelativeBounds names, because both are derived
        // from these same four numbers. It is also why the bounds stay RectF — rounding them to
        // whole points first lost enough precision on a 0..1 fraction to show up as seams.
        pdfFile.renderPageBitmap(render, renderingTask.page, renderingTask.bounds,
                renderingTask.annotationRendering);

        return new PagePart(renderingTask.page, render,
                renderingTask.bounds, renderingTask.thumbnail,
                renderingTask.cacheOrder);
    }

    void stop() {
        running = false;
    }

    void start() {
        running = true;
    }

    private class RenderingTask {

        final TileKey key;

        /**
         * 最近一次被请求时的 {@link RenderingHandler#generation}，用来判断它属于哪一轮。
         *
         * <p>不是 final：本轮仍然需要的排队任务会被 {@code addRenderingTask} 就地更新到当前轮
         * （见那里的注释），否则它会被 {@link #dropStaleTasks()} 误当成上一轮的残留丢掉。
         * 只在 {@link #queueLock} 内读写。
         */
        int generation;

        /** 已被取出来、正在画。只在 {@link #queueLock} 内读写。 */
        boolean inFlight;

        float width, height;

        RectF bounds;

        int page;

        boolean thumbnail;

        int cacheOrder;

        boolean bestQuality;

        boolean annotationRendering;

        RenderingTask(TileKey key, float width, float height, RectF bounds, int page, boolean thumbnail, int cacheOrder, boolean bestQuality, boolean annotationRendering, int generation) {
            this.key = key;
            this.generation = generation;
            this.page = page;
            this.width = width;
            this.height = height;
            this.bounds = bounds;
            this.thumbnail = thumbnail;
            this.cacheOrder = cacheOrder;
            this.bestQuality = bestQuality;
            this.annotationRendering = annotationRendering;
        }
    }
}
