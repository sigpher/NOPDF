package com.sigpher.nopdf.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录树展开/折叠逻辑的回归测试（纯 JVM，不依赖 Android）。
 *
 * @author aaronzzxup@gmail.com
 */
class ContentTreeTest {

    private fun leaf(title: String, pageIdx: Int) = ContentNode(title, pageIdx)

    private fun parent(title: String, pageIdx: Int, vararg children: ContentNode) =
            ContentNode(title, pageIdx, children.toList())

    private val chapter1 = parent(
            "第一章",
            0,
            leaf("1.1", 1),
            parent("1.2", 2, leaf("1.2.1", 3)),
            leaf("1.3", 4)
    )

    private val chapter2 = parent("第二章", 5, leaf("2.1", 6))

    @Test
    fun `默认只展示顶层节点`() {
        val rows = ContentTree(listOf(chapter1, chapter2)).rows()

        assertEquals(listOf("第一章", "第二章"), rows.map { it.title })
        assertEquals(listOf(0, 0), rows.map { it.depth })
        assertEquals(listOf(true, true), rows.map { it.hasChildren })
        assertEquals(listOf(false, false), rows.map { it.expanded })
    }

    @Test
    fun `展开后按深度优先展示直接子节点`() {
        val tree = ContentTree(listOf(chapter1, chapter2))

        assertTrue(tree.toggle(0))

        val rows = tree.rows()
        assertEquals(listOf("第一章", "1.1", "1.2", "1.3", "第二章"), rows.map { it.title })
        assertEquals(listOf(0, 1, 1, 1, 0), rows.map { it.depth })
        // 只有被展开的 第一章 是展开态；1.2 虽有子节点但尚未展开
        assertEquals(listOf(true, false, false, false, false), rows.map { it.expanded })
    }

    @Test
    fun `展开子树后层级递增`() {
        val tree = ContentTree(listOf(chapter1))

        tree.toggle(0) // 展开 第一章
        tree.toggle(2) // 展开 1.2

        val rows = tree.rows()
        assertEquals(listOf("第一章", "1.1", "1.2", "1.2.1", "1.3"), rows.map { it.title })
        assertEquals(listOf(0, 1, 1, 2, 1), rows.map { it.depth })
    }

    @Test
    fun `折叠后子节点不再展示`() {
        val tree = ContentTree(listOf(chapter1))

        tree.toggle(0)
        tree.toggle(0)

        val rows = tree.rows()
        assertEquals(listOf("第一章"), rows.map { it.title })
        assertFalse(rows[0].expanded)
    }

    @Test
    fun `叶子节点点击图标不改变状态`() {
        val tree = ContentTree(listOf(leaf("1.1", 1)))

        assertFalse(tree.toggle(0))
        assertEquals(1, tree.rows().size)
    }

    @Test
    fun `越界位置安全返回`() {
        val tree = ContentTree(listOf(chapter1))

        assertFalse(tree.toggle(3))
        assertFalse(tree.toggle(-1))
    }

    @Test
    fun `展开一个分支不影响另一个分支`() {
        val tree = ContentTree(listOf(chapter1, chapter2))

        tree.toggle(0) // 展开 第一章
        val expandedSecondRoot = 4 // 第一章展开后，第二章被挤到第 4 行
        tree.toggle(expandedSecondRoot)

        val rows = tree.rows()
        assertEquals(
                listOf("第一章", "1.1", "1.2", "1.3", "第二章", "2.1"),
                rows.map { it.title }
        )
        assertEquals(listOf(0, 1, 1, 1, 0, 1), rows.map { it.depth })
    }

    @Test
    fun `空树不产生任何行`() {
        assertEquals(0, ContentTree(emptyList()).rows().size)
    }
}
