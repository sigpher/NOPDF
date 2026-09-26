package com.sigpher.nopdf.common

import com.sigpher.nopdf.common.bean.Collection
import com.sigpher.nopdf.common.bean.Cover
import com.sigpher.nopdf.common.bean.PDF

/**
 * 由「分组列表 + 全部 PDF」构建书架首页的分组封面。
 *
 * 抽成纯函数是为了两件事：
 * 1. 旧实现 `DataManager.updateCoverList()` 对**每个分组**都调用一次 `getPdfList(name)`，
 *    而后者要全量扫描 `pdfList` 再排序一次，且返回的是同一个 static `tempList`（可被下一次
 *    调用清空）。分组一多就是 O(分组数 × PDF 数) 的主线程开销。这里改为一次 `groupBy`，
 *    总体降到 O(PDF 数 + 每组 k log k)。
 * 2. 不触碰 Android / DAO，可由 JVM 单元测试覆盖（见 CoverBuilderTest）。
 *
 * 语义与旧实现逐条对齐：按 [collections] 的顺序输出、空分组跳过、
 * 组内按 `position` **降序**取前 [MAX_COVER_COUNT] 张作为封面、`name` 取该组的目录名、
 * `count` 是该组 PDF **总数**（不是封面数）。
 */
object CoverBuilder {

    /** 每组展示的最大封面数，与旧实现的 subList 上限一致。 */
    const val MAX_COVER_COUNT = 4

    @JvmStatic
    fun build(pdfs: List<PDF>, collections: List<Collection>): List<Cover> {
        if (pdfs.isEmpty() || collections.isEmpty()) {
            return emptyList()
        }
        val byDir = pdfs.groupBy { it.dir }
        val result = ArrayList<Cover>(collections.size)
        for (collection in collections) {
            val group = byDir[collection.name] ?: continue
            if (group.isEmpty()) {
                continue
            }
            val ordered = group.sortedByDescending { it.position }
            val covers = ordered.take(MAX_COVER_COUNT).map { it.cover }
            result.add(Cover(ordered[0].dir, covers, ordered.size))
        }
        return result
    }
}
