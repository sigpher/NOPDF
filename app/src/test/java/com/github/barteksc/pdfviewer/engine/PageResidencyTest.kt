package com.github.barteksc.pdfviewer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PageResidency], the viewer's cap on how many pages the engine holds open.
 *
 * 中文用例名沿用仓库惯例。背景见 [PageResidency] 的类注释：这个策略存在的唯一理由，是
 * MuPDF 的 page 对象不释放就永远占着内存，而「页开失败」与「页只是没开着」如果不加区分，
 * 两边都会以「空白页」的形式表现出来——前者在稳定重现时是永久的，后者是每页都触发。
 */
class PageResidencyTest {

    private val released = mutableListOf<Int>()

    private fun residency(max: Int) = PageResidency(max, PageResidency.Releaser { released.add(it) })

    private fun PageResidency.hold(pages: IntRange) {
        pages.forEach { record(it, false) }
        trim()
    }

    @Test
    fun `超过上限时按最久未用的顺序释放`() {
        val residency = residency(3)
        residency.hold(0..4)
        assertEquals(listOf(0, 1), released)
        assertEquals(3, residency.size())
    }

    @Test
    fun `仍在使用的页不会被释放`() {
        val residency = residency(3)
        residency.hold(0..2)
        // 0 号是最久未用的，但下面又访问了它
        assertTrue(residency.isHeld(0))
        residency.record(3, false)
        residency.record(4, false)
        residency.trim()
        assertEquals(listOf(1, 2), released)
        assertTrue(residency.isHeld(0))
    }

    @Test
    fun `未持有的页不算失败`() {
        val residency = residency(3)
        // 这正是旧实现里让每一页都变空白的那一条：页只是没开着，却被当成开失败
        assertFalse(residency.hasFailed(7))
    }

    @Test
    fun `开失败会被记住`() {
        val residency = residency(3)
        residency.record(5, true)
        residency.trim()
        assertTrue(residency.hasFailed(5))
        assertTrue(residency.isHeld(5))
    }

    @Test
    fun `开失败的页不会去释放引擎里的东西`() {
        val residency = residency(2)
        residency.record(0, true)
        residency.hold(1..2)
        // 0 号最久未用被挤掉了，但它从未成功打开，引擎里没有它的 page，释放它是错的
        assertEquals(emptyList<Int>(), released)
        assertFalse(residency.isHeld(0))
    }

    @Test
    fun `失败标记随页一起过期`() {
        val residency = residency(2)
        residency.record(0, true)
        residency.hold(1..3)
        // 堆紧张导致的开页失败会自愈：标记被挤掉之后这一页还能重新打开
        assertFalse(residency.hasFailed(0))
    }

    @Test
    fun `上限以内的页一个都不会被释放`() {
        val residency = residency(8)
        residency.hold(0..7)
        assertEquals(emptyList<Int>(), released)
        assertEquals(8, residency.size())
    }
}
