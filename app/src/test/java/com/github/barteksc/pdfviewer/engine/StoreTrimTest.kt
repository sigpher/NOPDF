package com.github.barteksc.pdfviewer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [StoreTrim], the schedule on which the engine's resource store is emptied.
 *
 * 中文用例名沿用仓库惯例。背景见 [StoreTrim] 的类注释：MuPDF 的 store 归还有字体和图片，
 * 而它们的占用随**渲染过的页数**线性增长，与同时开着几页无关——实测 200 页文档不清理会
 * 涨到 438.9MB，每 8 页清一次峰值降到 18.8MB。这里钉的是「多久清一次」这个决策本身。
 */
class StoreTrimTest {

    private fun trimsIn(interval: Int, releases: Int): List<Int> {
        val trim = StoreTrim(interval)
        return (1..releases).filter { trim.onPageReleased() }
    }

    @Test
    fun `默认间隔是八页`() {
        assertEquals(8, StoreTrim().interval)
        assertEquals(StoreTrim.DEFAULT_INTERVAL, StoreTrim().interval)
    }

    @Test
    fun `在第 N 次释放时才清理而不是提前`() {
        // 提前清理等于白白付出遍历 store 的代价，而那时还没攒下多少东西。
        assertEquals(listOf(8, 16), trimsIn(interval = 8, releases = 20))
    }

    @Test
    fun `每隔 N 次释放清理一次`() {
        assertEquals(listOf(4, 8, 12, 16, 20), trimsIn(interval = 4, releases = 20))
        assertEquals(listOf(16), trimsIn(interval = 16, releases = 20))
    }

    @Test
    fun `清理之后重新计数而不是接着数`() {
        // 若清理后不归零，第二次清理就会提前到第 9+interval-1 次，间隔越走越短。
        val trim = StoreTrim(3)
        assertFalse(trim.onPageReleased())
        assertFalse(trim.onPageReleased())
        assertTrue(trim.onPageReleased())
        assertFalse(trim.onPageReleased())
        assertFalse(trim.onPageReleased())
        assertTrue(trim.onPageReleased())
    }

    @Test
    fun `间隔为一时每次释放都清理`() {
        assertEquals(listOf(1, 2, 3), trimsIn(interval = 1, releases = 3))
    }

    @Test
    fun `重置后从第一次释放重新数起`() {
        // 换文档时用：上一本文档攒下的计数不该让新文档一上来就清理。
        val trim = StoreTrim(4)
        repeat(3) { assertFalse(trim.onPageReleased()) }
        trim.reset()
        assertFalse(trim.onPageReleased())
        assertFalse(trim.onPageReleased())
        assertFalse(trim.onPageReleased())
        assertTrue(trim.onPageReleased())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `间隔为零是非法的`() {
        // 否则 onPageReleased 会永远返回 true，退化成每释放一页就清理一次。
        StoreTrim(0)
    }
}
