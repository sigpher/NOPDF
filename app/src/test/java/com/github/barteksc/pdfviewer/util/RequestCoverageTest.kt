package com.github.barteksc.pdfviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fmt(v: Float): Float = Math.round(v * 10000f) / 10000f

/**
 * 「屏内画得出来的格子 ⊆ 本轮请求的格子集合」——不变量，而不是复现某个 bug。
 *
 * <h3>为什么要有这个测试</h3>
 *
 * 「快速滑动长文档后整屏全白，空白后之前正常的页也变空白」这个症状，第七轮才拿到一条决定性
 * 的线索：<b>之前正常的页也变空白</b>。这说明画出来的东西<b>不见了</b>，而不是没画出来。
 * 而整条请求+缓存链路（{@code PagesLoader} 的范围计算 → {@code upPartIfContained} →
 * {@code RenderSchedule.order} → {@code PartCache}）在结构上<b>不依赖 Android</b>：唯一需要
 * Android 的只是 {@code RectF}，而它在这里只被用来装四个 float。所以它可以被完整模拟，
 * <b>本机就能验</b>——这正是 AGENTS.md 那条教训的适用范围。
 *
 * <p>链条上任一环出错都会产生这个症状，而且症状完全一样，所以「读代码看着对」不构成结论。
 * 这里把它变成可证伪的断言：把两侧（{@link #drawableCells} 与 {@link #collectCells}）各自
 * 独立算出来，然后断言前者是后者的子集。两侧的代码路径故意不共用任何中间结果。
 *
 * <h3>刻意不测什么</h3>
 *
 * <p>不测渲染速度、不测队列调度、不测 MuPDF。这里只钉住「请求集合覆盖得上绘制集合」这一条。
 * 渲染效果本身（缩放、翻页、页面间隔）依旧没有自动化覆盖，升级前仍需手动过一遍。
 */
class RequestCoverageTest {

    /** 一格分块在页内的矩形（0..1）。刻意不叫 RectF：那个是 Android 的。 */
    private class Cell(val page: Int, val l: Float, val t: Float, val r: Float, val b: Float) {
        override fun equals(other: Any?): Boolean =
            other is Cell && other.page == page && other.l == l && other.t == t &&
                    other.r == r && other.b == b

        override fun hashCode(): Int {
            var h = page
            h = 31 * h + java.lang.Float.floatToIntBits(l)
            h = 31 * h + java.lang.Float.floatToIntBits(t)
            h = 31 * h + java.lang.Float.floatToIntBits(r)
            h = 31 * h + java.lang.Float.floatToIntBits(b)
            return h
        }

        override fun toString(): String = "p$page[${fmt(l)},${fmt(t)}..${fmt(r)},${fmt(b)}]"
    }

    private val alive = HashSet<Cell>()

    private fun newCache(): PartCache<Cell> = PartCache(object : PartCache.Adapter<Cell> {
        override fun same(a: Cell, b: Cell): Boolean = a == b
        override fun alive(item: Cell): Boolean = alive.contains(item)
    })

    // ---- 文档与视口的几何。全部照抄 PagesLoader / PdfFile 的算法，不是重新发明的 ----

    private val pageCount = 200
    private val pageWidth = 1080f      // 缩放后的页宽（fit-width）
    private val pageHeight = 1528f     // 缩放后的页高
    private val viewWidth = 1080
    private val viewHeight = 2400
    private val zoom = 1f
    private val partSize = Constants.PART_SIZE
    private val cacheSize = Constants.Cache.CACHE_SIZE
    private val preloadOffset = 60f     // PRELOAD_OFFSET 20dp，按 3 倍密度折成 px

    private val pageOffsets = FloatArray(pageCount) { it * pageHeight }
    private val documentLength = pageCount * pageHeight

    private fun pageOffset(page: Int) = pageOffsets[page] * zoom

    private fun toCurrentScale(v: Float) = v * zoom

    /** PagesLoader.getPageColsRows */
    private fun rowsOf(page: Int): Int {
        val ratioY = 1f / pageHeight
        val partHeight = (partSize * ratioY) / zoom
        return MathUtils.ceil(1f / partHeight)
    }

    private fun colsOf(page: Int): Int {
        val ratioX = 1f / pageWidth
        val partWidth = (partSize * ratioX) / zoom
        return MathUtils.ceil(1f / partWidth)
    }

    /**
     * PDFView.drawPart 的视口剔除条件，原样照搬。
     *
     * <p>画出来当且仅当 [translationY + dstTop < 视口高] 且 [translationY + dstBottom > 0]，
     * 其中 translationY = currentYOffset + 该页在 strip 里的偏移。
     */
    private fun isDrawable(cell: Cell, currentYOffset: Float): Boolean {
        val translationY = currentYOffset + pageOffset(cell.page)
        val dstTop = toCurrentScale(cell.t * pageHeight)
        val dstBottom = toCurrentScale(cell.b * pageHeight)
        return translationY + dstTop < viewHeight && translationY + dstBottom > 0
    }

    /** 视口内画得出来的全部格子。不经过任何缓存逻辑。 */
    private fun drawableCells(currentYOffset: Float): Set<Cell> {
        val out = LinkedHashSet<Cell>()
        // 视口上下各留 preloadOffset，够到多少页就算多少页
        val top = -currentYOffset - preloadOffset
        val bottom = -currentYOffset + viewHeight + preloadOffset
        for (page in 0 until pageCount) {
            if (pageOffset(page) + toCurrentScale(pageHeight) < top) continue
            if (pageOffset(page) > bottom) break
            val rows = rowsOf(page)
            val cols = colsOf(page)
            val rowHeight = toCurrentScale(pageHeight) / rows
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val rl = 1f / cols * col
                    val rt = 1f / rows * row
                    val rr = if (rl + 1f / cols > 1f) 1f else rl + 1f / cols
                    val rb = if (rt + 1f / rows > 1f) 1f else rt + 1f / rows
                    val c = Cell(page, rl, rt, rr, rb)
                    if (isDrawable(c, currentYOffset)) out.add(c)
                }
            }
        }
        return out
    }

    /**
     * PagesLoader.collectCells 枚举的格子（只看「不在缓存里」的那部分之前，先枚举全部）。
     *
     * <p>行/列区间照抄 PagesLoader.getRenderRangeList 的竖向分支，含它那个 ceil/floor 的
     * 边界处理——**包括不夹到 [0, rows-1]** 这一点，正是这里最可能出问题。
     */
    private fun collectCells(currentYOffset: Float): List<Cell> {
        val yOffset = -MathUtils.max(currentYOffset, 0f)
        val firstY = -yOffset + preloadOffset
        val lastY = -yOffset - viewHeight - preloadOffset
        val fixedFirst = -MathUtils.max(firstY, 0f)
        val fixedLast = -MathUtils.max(lastY, 0f)

        val firstPage = pageAtOffset(fixedFirst)
        val lastPage = pageAtOffset(fixedLast)

        val out = ArrayList<Cell>()
        for (page in firstPage..lastPage) {
            val rows = rowsOf(page)
            val cols = colsOf(page)
            val rowHeight = toCurrentScale(pageHeight) / rows
            val off = pageOffset(page)

            val pageFirstY: Float
            val pageLastY: Float
            if (page == firstPage) {
                pageFirstY = fixedFirst
                pageLastY = if (firstPage == lastPage) fixedLast else off + toCurrentScale(pageHeight)
            } else if (page == lastPage) {
                pageFirstY = off
                pageLastY = fixedLast
            } else {
                pageFirstY = off
                pageLastY = off + toCurrentScale(pageHeight)
            }

            val leftTopRow = MathUtils.floor(Math.abs(pageFirstY - off) / rowHeight)
            val rightBottomRow = MathUtils.ceil(Math.abs(pageLastY - off) / rowHeight)

            for (row in leftTopRow..rightBottomRow) {
                for (col in 0 until cols) {
                    val relX = 1f / cols * col
                    val relY = 1f / rows * row
                    var relW = 1f / cols
                    var relH = 1f / rows
                    if (relX + relW > 1f) relW = 1f - relX
                    if (relY + relH > 1f) relH = 1f - relY
                    // PagesLoader：渲染尺寸非正就整格跳过
                    val renderW = (partSize / (1f / cols)) * relW
                    val renderH = (partSize / (1f / rows)) * relH
                    if (renderW <= 0f || renderH <= 0f) continue
                    out.add(Cell(page, relX, relY, relX + relW, relY + relH))
                }
            }
        }
        return out
    }

    /** PdfFile.getPageAtOffset */
    private fun pageAtOffset(offset: Float): Int {
        var current = 0
        for (i in 0 until pageCount) {
            val off = pageOffsets[i] * zoom
            if (off >= offset) break
            current++
        }
        return if (--current >= 0) current else 0
    }

    // ---- 被测组件：真实的 PartCache 与 RenderSchedule ----

    /** 一次「渲染线程把这一轮排队的都画完了」的完整循环。 */
    private fun drain(cache: PartCache<Cell>, currentYOffset: Float) {
        var pass = 0
        while (pass < 40) {
            pass++
            cache.newPass()
            val requested = ArrayList<Cell>()
            for (c in collectCells(currentYOffset)) {
                if (!cache.promote(c)) requested.add(c)
            }
            if (requested.isEmpty()) return
            // RenderSchedule.order：按离当前页的距离排序，再套预算
            val currentPage = pageAtOffset(-currentYOffset)
            val reqs = ArrayList<RenderSchedule.Request>()
            requested.forEachIndexed { i, c ->
                reqs.add(RenderSchedule.Request(i, c.page, Math.abs(c.page - currentPage), false))
            }
            val ordered = RenderSchedule.order(reqs, cacheSize, Constants.Cache.THUMBNAILS_CACHE_SIZE)
            var rendered = 0
            for (r in ordered) {
                val c = requested[r.index]
                cache.trimTo(cacheSize).forEach { alive.remove(it) }
                cache.add(c)
                alive.add(c)
                rendered++
            }
            if (rendered == 0) return
        }
    }

    // ---- 用例 ----

    /**
     * 静止时，屏内每一格都必须在缓存里。
     *
     * <p>这一条等价于「屏幕不应该是白的」。它不区分病因，只断言结果：不管中间发生了什么，
     * 停下来之后画面必须是满的。
     */
    @Test
    fun `静止后屏内每一格都在缓存里`() {
        val cache = newCache()
        // 逐页走到每一个位置，模拟「一路滑过去」
        for (page in 0 until pageCount) {
            val offset = -page * pageHeight
            drain(cache, offset)
            val missing = drawableCells(offset).filter { !alive.contains(it) }
            assertTrue(
                "第 $page 页静止后有 ${missing.size} 格画得出来却不在缓存里：${missing.take(5)}",
                missing.isEmpty()
            )
        }
    }

    /**
     * 「空白后，之前正常的页面也会变成空白」——直接照着这句话写。
     *
     * <p>滑到第 8 页并等它画完（此刻第 8 页是好的），再滑走 20 页，再滑回来。回来之后第 8 页
     * 的每一格都必须在缓存里。缓存只装得下 [cacheSize] 格（约四页），所以滑走 20 页必然把
     * 第 8 页全部淘汰掉——**这一页必须能被重新画出来**。不能重新画出来，就正好是用户说的那句。
     */
    @Test
    fun `滑走再滑回来，之前正常的页必须能重新画出来`() {
        val cache = newCache()
        val home = -8 * pageHeight

        drain(cache, home)
        val atHome = drawableCells(home)
        assertTrue("先确认第 8 页是画得出来的", atHome.isNotEmpty())
        assertTrue("先确认第 8 页起初画满了", atHome.all { alive.contains(it) })

        // 滑走 20 页：第 8 页必然被淘汰
        for (page in 9..28) drain(cache, -page * pageHeight)
        val stillThere = atHome.count { alive.contains(it) }
        println("滑走 20 页后第 8 页仍在缓存的格数：$stillThere / ${atHome.size}")

        // 滑回来
        drain(cache, home)
        val missing = atHome.filter { !alive.contains(it) }
        assertTrue(
            "滑回来后第 8 页有 ${missing.size} 格没能重新画出来：${missing.take(5)}",
            missing.isEmpty()
        )
    }

    /** 一次跳很远（快速滑动 / 甩动）之后停在任意位置，屏内都必须是满的。 */
    @Test
    fun `大跨度跳停后屏内必须是满的`() {
        val cache = newCache()
        val jumps = intArrayOf(0, 1, 5, 9, 16, 16, 40, 77, 120, 199, 3, 61, 12, 88, 150)
        for (p in jumps) {
            val offset = -p * pageHeight
            drain(cache, offset)
            val missing = drawableCells(offset).filter { !alive.contains(it) }
            assertTrue("跳到第 $p 页后缺 ${missing.size} 格：${missing.take(5)}", missing.isEmpty())
        }
    }

    /**
     * 渲染线程被饿死的情形：每轮只画少量格子，滑动的中间过程照常发生。
     *
     * <p>上一条测的是「停下来之后画得满」。这一条测的是**滑动的过程中**每格最终都能轮到——
     * 因为真机上「空白是永久的」意味着停下来也没能补上，所以两种饿死程度都要覆盖。
     */
    @Test
    fun `渲染线程很慢时，滑完之后屏内仍必须是满的`() {
        val cache = newCache()
        // 快速滑动：每一帧的视口都跳 3 页，但每帧只画 4 格
        for (page in 0 until pageCount step 3) {
            cache.newPass()
            var budget = 4
            for (c in collectCells(-page * pageHeight)) {
                if (budget <= 0) break
                if (cache.promote(c)) continue
                cache.trimTo(cacheSize).forEach { alive.remove(it) }
                cache.add(c)
                alive.add(c)
                budget--
            }
        }
        // 停下来
        val rest = -100 * pageHeight
        drain(cache, rest)
        val missing = drawableCells(rest).filter { !alive.contains(it) }
        assertTrue("慢速渲染下停到第 100 页仍缺 ${missing.size} 格：${missing.take(5)}", missing.isEmpty())
    }

    /** 顺手把两个常量关系钉住：缓存能装下几页，正是「之前正常的页会被淘汰」的由来。 */
    @Test
    fun `每页格数与缓存能装下几页`() {
        val perPage = rowsOf(0) * colsOf(0)
        println("每页 $perPage 格，缓存 $cacheSize 格 ≈ ${cacheSize / perPage} 页")
        assertTrue("每页格数应当是几十量级，实际 $perPage", perPage in 10..100)
        assertEquals("一次只需要一页的格子就够填满视口", true, perPage <= cacheSize)
    }
}
