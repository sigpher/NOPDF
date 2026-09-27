package com.github.barteksc.pdfviewer.util

import com.github.barteksc.pdfviewer.util.RenderSchedule.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染请求的排队顺序就是优先级，而「快速滑动后整页空白」正是优先级排错造成的
 * （见 [RenderSchedule] 与 AGENTS.md）。这里钉住那三条规则。
 */
class RenderScheduleTest {

    private fun cell(index: Int, page: Int, distance: Int) =
        Request(index, page, distance, false)

    private fun thumb(index: Int, page: Int, distance: Int) =
        Request(index, page, distance, true)

    /** 把结果压成 "页:类型" 的列表，便于断言顺序。 */
    private fun List<Request>.labels() = map { "${it.page}:${if (it.thumbnail) "t" else "c"}" }

    @Test
    fun `分块排在缩略图前面，哪怕缩略图先传进来`() {
        val requests = listOf(thumb(0, 5, 0), thumb(1, 6, 1), cell(2, 5, 0), cell(3, 6, 1))

        val ordered = RenderSchedule.order(requests, RenderSchedule.UNLIMITED, RenderSchedule.UNLIMITED)

        assertEquals(listOf("5:c", "6:c", "5:t", "6:t"), ordered.labels())
    }

    @Test
    fun `离视口近的分块先画`() {
        // 故意按「先远后近」传入，顺序必须被纠正过来。
        val requests = listOf(cell(0, 30, 12), cell(1, 31, 11), cell(2, 29, 1), cell(3, 32, 10))

        val ordered = RenderSchedule.order(requests, RenderSchedule.UNLIMITED, RenderSchedule.UNLIMITED)

        // 距离 1 → 10 → 11 → 12
        assertEquals(listOf(2, 3, 1, 0), ordered.map { it.index })
    }

    @Test
    fun `同距离时保持传入次序，也就是一页内的行优先顺序`() {
        val requests = listOf(cell(0, 5, 0), cell(1, 5, 0), cell(2, 5, 0), cell(3, 5, 0))

        val ordered = RenderSchedule.order(requests, RenderSchedule.UNLIMITED, RenderSchedule.UNLIMITED)

        assertEquals(listOf(0, 1, 2, 3), ordered.map { it.index })
    }

    @Test
    fun `这一轮没有分块的页，其缩略图被丢掉`() {
        // 第 7 页的分块全在缓存里，所以这一轮不需要重画它——它的缩略图画出来也立刻被盖住。
        val requests = listOf(cell(0, 6, 0), thumb(1, 6, 0), thumb(2, 7, 1))

        val ordered = RenderSchedule.order(requests, RenderSchedule.UNLIMITED, RenderSchedule.UNLIMITED)

        assertEquals(listOf("6:c", "6:t"), ordered.labels())
    }

    @Test
    fun `分块被预算截断时那一页仍保留缩略图，因为没排上的那几格画出来之前要靠它顶着`() {
        // 预算只够 1 格，但第 8 页确实有分块要画，所以它的缩略图仍有用。
        val requests = listOf(cell(0, 8, 0), cell(1, 8, 0), cell(2, 8, 0), thumb(3, 8, 0))

        val ordered = RenderSchedule.order(requests, 1, RenderSchedule.UNLIMITED)

        assertEquals(listOf("8:c", "8:t"), ordered.labels())
    }

    @Test
    fun `分块预算生效`() {
        val requests = (0 until 5).map { cell(it, 10, it) }

        val ordered = RenderSchedule.order(requests, 3, RenderSchedule.UNLIMITED)

        assertEquals(listOf(0, 1, 2), ordered.map { it.index })
    }

    @Test
    fun `缩略图有自己独立的预算，不占分块的名额`() {
        val requests = (0 until 4).map { cell(it, 20, 0) } + (0 until 4).map { thumb(4 + it, 20, 0) }

        val ordered = RenderSchedule.order(requests, 2, 1)

        // 4 个分块请求只排得下 2 个，2 个名额没有被缩略图吃掉；缩略图另得 1 个。
        assertEquals(listOf("20:c", "20:c", "20:t"), ordered.labels())
    }

    @Test
    fun `预算比请求多时不会越界`() {
        val requests = listOf(cell(0, 1, 0), thumb(1, 1, 0))

        val ordered = RenderSchedule.order(requests, 99, 99)

        assertEquals(2, ordered.size)
    }

    @Test
    fun `没有请求时返回空列表`() {
        assertTrue(RenderSchedule.order(emptyList(), RenderSchedule.UNLIMITED, RenderSchedule.UNLIMITED).isEmpty())
    }
}
