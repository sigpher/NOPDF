package com.sigpher.nopdf.common

import com.sigpher.nopdf.common.bean.Collection
import com.sigpher.nopdf.common.bean.PDF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CoverBuilder] 的回归测试（纯 JVM，不依赖 Android）。
 *
 * 覆盖从 `DataManager.updateCoverList()` 抽成纯函数时对齐的旧语义：按 [Collection] 的顺序输出、
 * 空分组跳过、组内按 `position` 降序取前 [CoverBuilder.MAX_COVER_COUNT] 张作封面、
 * `count` 是该组 PDF **总数**而非封面数。
 */
class CoverBuilderTest {

    private fun pdf(dir: String, position: Int, cover: String = "$dir#$position.jpg"): PDF {
        val p = PDF()
        p.setDir(dir)
        p.setPosition(position)
        p.setCover(cover)
        return p
    }

    private fun col(name: String, position: Int = 0) = Collection(name, position)

    @Test
    fun `按 collections 的顺序输出分组`() {
        val covers = CoverBuilder.build(
                listOf(pdf("A", 0), pdf("B", 0)),
                listOf(col("B"), col("A"))
        )
        assertEquals(listOf("B", "A"), covers.map { it.name })
    }

    @Test
    fun `空分组被跳过`() {
        val covers = CoverBuilder.build(
                listOf(pdf("A", 0)),
                listOf(col("A"), col("Empty"))
        )
        assertEquals(listOf("A"), covers.map { it.name })
    }

    @Test
    fun `组内按 position 降序取前四张作为封面`() {
        val covers = CoverBuilder.build((1..6).map { pdf("A", it) }, listOf(col("A")))
        assertEquals(1, covers.size)
        assertEquals(CoverBuilder.MAX_COVER_COUNT, covers[0].coverList.size)
        assertEquals(listOf("A#6.jpg", "A#5.jpg", "A#4.jpg", "A#3.jpg"), covers[0].coverList)
    }

    @Test
    fun `count 是该组 PDF 总数而非封面数`() {
        val covers = CoverBuilder.build((1..6).map { pdf("A", it) }, listOf(col("A")))
        assertEquals(6, covers[0].count)
    }

    @Test
    fun `封面不足四张时全部使用`() {
        val covers = CoverBuilder.build(listOf(pdf("A", 1), pdf("A", 2)), listOf(col("A")))
        assertEquals(listOf("A#2.jpg", "A#1.jpg"), covers[0].coverList)
        assertEquals(2, covers[0].count)
    }

    @Test
    fun `name 取该组的目录名`() {
        val covers = CoverBuilder.build(listOf(pdf("Books/Math", 0)), listOf(col("Books/Math")))
        assertEquals("Books/Math", covers[0].name)
    }

    @Test
    fun `输入为空时返回空列表`() {
        assertTrue(CoverBuilder.build(emptyList(), emptyList()).isEmpty())
        assertTrue(CoverBuilder.build(listOf(pdf("A", 0)), emptyList()).isEmpty())
        assertTrue(CoverBuilder.build(emptyList(), listOf(col("A"))).isEmpty())
    }
}
