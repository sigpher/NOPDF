package com.github.barteksc.pdfviewer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PartCache] 的行为约束。
 *
 * 这不是在测「淘汰对不对」，而是在钉住一条**曾经真实发生过的**链：两层缓存里同一个条目同时
 * 存在 → 淘汰时回收掉其中一份的位图 → 剩下那份是死条目 → 绘制时跳过、查询时却当成命中 →
 * **那一格永久空白，只有重载文档才能恢复**。完整推导见 [PartCache] 的类注释。
 *
 * 纯 JVM，不依赖 Android framework。
 */
class PartCacheTest {

    /** 假条目：只有格子的坐标，外加一个可手动置为「已回收」的 alive 标记。 */
    private class Tile(val page: Int, val col: Int, val row: Int, var alive: Boolean = true) {
        override fun equals(other: Any?): Boolean {
            if (other !is Tile) return false
            return other.page == page && other.col == col && other.row == row
        }

        override fun hashCode(): Int = (page * 31 + col) * 31 + row
        override fun toString(): String = "Tile(p=$page,c=$col,r=$row,alive=$alive)"
    }

    private val adapter = object : PartCache.Adapter<Tile> {
        override fun same(a: Tile, b: Tile): Boolean = a == b
        override fun alive(item: Tile): Boolean = item.alive
    }

    private fun cache() = PartCache<Tile>(adapter)

    private fun tile(page: Int, col: Int, row: Int) = Tile(page, col, row)

    @Test
    fun `promote 命中 passive 里的条目并把它当作最近用过`() {
        val cache = cache()
        val t = tile(1, 0, 0)
        cache.add(t)
        cache.newPass()

        assertTrue(cache.promote(tile(1, 0, 0)))
        // 命中的是**那个对象本身**，不是某个相等的替身。
        assertSame(t, cache.entries()[cache.entries().size - 1])
    }

    @Test
    fun `promote 未命中时返回 false`() {
        val cache = cache()
        assertFalse(cache.promote(tile(7, 3, 3)))
    }

    /**
     * 核心回归：死条目必须**不算命中**，否则那一格永远不会被重新请求。
     * 这正是「整页空白、只有切换主题才恢复」的最后一步。
     */
    @Test
    fun `位图已回收的条目不算命中，于是那一格会被重新请求`() {
        val cache = cache()
        val dead = tile(1, 0, 0)
        cache.add(dead)
        cache.newPass()
        dead.alive = false   // 淘汰时 recycle 了它的位图，但条目还留在某一层里

        assertFalse("死条目不能被当成「已经在缓存里」", cache.promote(tile(1, 0, 0)))
    }

    @Test
    fun `死条目会被清掉，不再占名额`() {
        val cache = cache()
        val dead = tile(1, 0, 0)
        cache.add(dead)
        cache.newPass()
        dead.alive = false

        cache.promote(tile(1, 1, 1))   // 一次不相关的扫描

        assertEquals(0, cache.size())
    }

    /**
     * 结构性保证：同一格被渲染两遍会送来两个**相等但不同**的对象，缓存里任何时候都只能留一个。
     * 旧实现（两个 PriorityQueue）正是在这里让同一个对象同时存在于两层。
     */
    @Test
    fun `同一格被加入两次时只留一份，且不跨层重复`() {
        val cache = cache()
        val first = tile(1, 0, 0)
        val second = tile(1, 0, 0)   // 相等，不同对象：同一次渲染被投递了两遍
        cache.add(first)

        val displaced = cache.add(second)

        assertEquals(1, cache.size())
        // 新来的那个接管；被顶掉的那个连同它的位图由调用方负责回收。
        assertSame(second, cache.entries()[0])
        assertSame("被顶掉的条目要交还给调用方，否则每次重复渲染都漏一张位图", first, displaced)
    }

    @Test
    fun `没有相等条目时 add 不交还任何东西`() {
        val cache = cache()
        assertNull(cache.add(tile(1, 0, 0)))
        assertNull(cache.add(tile(2, 0, 0)))
    }

    @Test
    fun `add 会把另一层里相等的条目清掉，不会让同一条目存在于两层`() {
        val cache = cache()
        val stale = tile(1, 0, 0)
        cache.add(stale)
        cache.newPass()               // stale 降级到 passive

        val fresh = tile(1, 0, 0)     // 同一格又渲染了一遍
        cache.add(fresh)              // 进 active

        // 只有一个条目，且它在 active（entries() 是 passive 在前、active 在后）。
        assertEquals(1, cache.size())
        assertSame(fresh, cache.entries()[0])
    }

    @Test
    fun `promote 之后条目只存在于 active 一层`() {
        val cache = cache()
        val t = tile(1, 0, 0)
        cache.add(t)
        cache.newPass()               // t 在 passive
        cache.promote(tile(1, 0, 0))  // 挪到 active

        // 再来一轮：只有 active 里的会被降级。若 promote 留下了跨层副本，
        // passive.add() 会因为 Set 去重而「看起来正常」，所以这里检查身份与层数。
        cache.newPass()
        assertEquals(1, cache.size())
        assertSame(t, cache.entries()[0])
    }

    // ---- 淘汰顺序（LRU）：passive 里的一律比 active 里的老 ----

    @Test
    fun `trimTo 先淘汰 passive 里最老的`() {
        val cache = cache()
        val a = tile(1, 0, 0)
        val b = tile(2, 0, 0)
        cache.add(a)
        cache.add(b)
        cache.newPass()               // passive = [a, b]，a 更老

        val evicted = cache.trimTo(2)

        assertEquals(1, evicted.size)
        assertSame(a, evicted[0])
        assertSame(b, cache.entries()[0])
    }

    @Test
    fun `trimTo 在 passive 空时淘汰 active 里最老的`() {
        val cache = cache()
        val a = tile(1, 0, 0)
        val b = tile(2, 0, 0)
        cache.add(a)
        cache.add(b)

        val evicted = cache.trimTo(2)

        assertEquals(1, evicted.size)
        assertSame(a, evicted[0])
    }

    @Test
    fun `promote 会刷新次序，于是反复使用的条目最后被淘汰`() {
        val cache = cache()
        val a = tile(1, 0, 0)
        val b = tile(2, 0, 0)
        cache.add(a)
        cache.add(b)
        cache.newPass()               // passive = [a, b]

        cache.promote(tile(1, 0, 0))  // a 变最近用过 → active = [a]，passive = [b]

        val evicted = cache.trimTo(2)

        assertEquals(1, evicted.size)
        assertSame(b, evicted[0])
    }

    @Test
    fun `trimTo 淘汰到 size 小于 maxSize 为止`() {
        val cache = cache()
        repeat(5) { cache.add(tile(it, 0, 0)) }

        val evicted = cache.trimTo(3)

        // 条件是 size < maxSize（对应旧实现的 total >= CACHE_SIZE 就淘汰一个，
        // 之后调用方才放新的进来），所以 5 个淘汰到剩 2 个。
        assertEquals(3, evicted.size)
        assertEquals(2, cache.size())
    }

    @Test
    fun `trimTo 在缓存本来就小于上限时什么都不做`() {
        val cache = cache()
        cache.add(tile(1, 0, 0))

        assertTrue(cache.trimTo(10).isEmpty())
        assertEquals(1, cache.size())
    }

    // ---- 杂项 ----

    @Test
    fun `entries 跳过死条目`() {
        val cache = cache()
        val alive = tile(1, 0, 0)
        val dead = tile(2, 0, 0)
        cache.add(alive)
        cache.add(dead)
        cache.newPass()
        dead.alive = false

        val entries = cache.entries()

        assertEquals(1, entries.size)
        assertSame(alive, entries[0])
    }

    @Test
    fun `newPass 丢弃已经死掉的 active 条目`() {
        val cache = cache()
        val dead = tile(1, 0, 0)
        cache.add(dead)
        dead.alive = false

        cache.newPass()

        assertEquals(0, cache.size())
    }

    /**
     * passive 里残留的同格死条目不能把待降级的活条目挤掉。LinkedHashSet.add 会拒绝已存在的键，
     * 不清掉的话活的那个被静默丢弃、位图成了孤儿，而死条目还占着名额。
     */
    @Test
    fun `newPass 遇到 passive 里的同格死条目时，活的那个仍能降级进去`() {
        val cache = cache()
        val dead = tile(1, 0, 0)
        cache.add(dead)
        cache.newPass()               // dead 在 passive
        dead.alive = false

        val live = tile(1, 0, 0)
        cache.add(live)                // 进 active
        cache.newPass()                // live 应当降级，而 dead 让位

        assertEquals(1, cache.size())
        assertSame(live, cache.entries()[0])
    }

    @Test
    fun `clear 清空两层`() {
        val cache = cache()
        cache.add(tile(1, 0, 0))
        cache.add(tile(2, 0, 0))
        cache.newPass()

        cache.clear()

        assertEquals(0, cache.size())
        assertTrue(cache.entries().isEmpty())
    }

    @Test
    fun `add 一个死条目不会让它占住名额`() {
        val cache = cache()
        val dead = tile(1, 0, 0)
        cache.add(dead)
        cache.newPass()
        dead.alive = false

        cache.add(tile(2, 0, 0))

        assertEquals(1, cache.size())
        assertNull(cache.entries().firstOrNull { it === dead })
    }
}
