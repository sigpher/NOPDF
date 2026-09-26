package com.sigpher.nopdf.common

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.sigpher.nopdf.common.bean.Cover
import com.sigpher.nopdf.common.bean.PDF

/**
 * 书架列表的 DiffUtil 局部刷新器，替代 `notifyDataSetChanged()` / BRVAH 的 `setNewData`。
 *
 * 为什么需要它：BRVAH 2.9.46 没有内置 diff 支持（无 `setDiffCallback`，也没有 diff 相关类），
 * `setNewData` / `replaceData` 内部都是 `notifyDataSetChanged()` —— 会把**所有可见 item
 * 全部重绑**（封面走 Glide 重新加载、选中态重算），书架数据一变就是整屏闪烁。
 *
 * ## 为什么由自己记录「上次显示过的列表」
 * `DataManager` 的列表是**原地 clear+addAll**，且各 Fragment 在构造时就把同一个实例交给了
 * Adapter（BRVAH 的 `getData()` 直接返回内部 `mData`，`AbstractAdapter` 直接持有
 * `sourceList`），即 `adapter.data === DataManager.xxxList`。这种共享可变列表下，
 * 「刷新时取一次旧快照」是拿不到屏幕当前内容的：数据可能早就被别处改掉了
 * （例如阅读页 `onPause` 改进度、`CollectionFragment2.onStop` 改分组后只发个事件通知）。
 *
 * 所以这里记住的是**上一次真正提交给 UI 的内容副本**，与「谁在什么时候改了共享列表」无关：
 * 每次提交都用「上次显示的内容」对比「现在的实时内容」。首次提交（[shown] 为空）退化为
 * 全量刷新。
 *
 * ## 空数据必须全量刷新
 * 数据为空时两个 Adapter 都会多出一个「空视图项」（BRVAH 经 `bindToRecyclerView` 设的
 * emptyView；`AbstractAdapter` 的 `TYPE_EMPTY`），`getItemCount()` 会比数据多 1，
 * 数据下标与 adapter 下标不再对齐，派发 diff 会发出错位通知（RecyclerView 会崩）。
 * 因此 [shown] 或当前数据为空时一律退回 `notifyDataSetChanged()`。
 *
 * ## 使用前提
 * 调用 [submit] 时，Adapter 内部的列表必须**已经**等于 [current]（共享列表天然满足；
 * 自有列表需先原地填好）。只能在主线程使用。
 */
class ListDiffer<T>(
        private val areItemsTheSame: (T, T) -> Boolean,
        private val areContentsTheSame: (T, T) -> Boolean
) {

    /** 上一次提交给 UI 的内容副本（不是引用，必须复制）。 */
    private var shown: List<T> = emptyList()

    fun submit(adapter: RecyclerView.Adapter<*>, current: List<T>) {
        val old = shown
        shown = ArrayList(current)
        if (old.isEmpty() || current.isEmpty()) {
            adapter.notifyDataSetChanged()
            return
        }
        val callback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size

            override fun getNewListSize(): Int = current.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    areItemsTheSame(old[oldItemPosition], current[newItemPosition])

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                    areContentsTheSame(old[oldItemPosition], current[newItemPosition])
        }
        // detectMoves = true：书架支持拖拽排序，必须识别移动，否则会被当成「删除 + 插入」。
        DiffUtil.calculateDiff(callback, true).dispatchUpdatesTo(adapter)
    }
}

/**
 * [Cover] 没有 equals（见 bean 定义），这里按「条目身份」比较：
 * `name` 即分组名，同一分组在刷新前后保持同一身份。
 */
fun coverIdentityTheSame(old: Cover, new: Cover): Boolean = old.name == new.name

/** [Cover] 的展示内容：四张封面图 + 总数。 */
fun coverContentTheSame(old: Cover, new: Cover): Boolean =
        old.count == new.count && old.coverList == new.coverList

/**
 * [PDF] 同样没有 equals（GreenDAO 实体只生成 getter/setter）。
 * `path` 是业务上的唯一键（`DBHelper.queryPDFByPath` 即以此查书）。
 */
fun pdfIdentityTheSame(old: PDF, new: PDF): Boolean = old.path == new.path

/** [PDF] 的展示内容：条目上会显示的字段（书名、封面、进度）+ 影响排序的字段。 */
fun pdfContentTheSame(old: PDF, new: PDF): Boolean =
        old.name == new.name &&
                old.cover == new.cover &&
                old.progress == new.progress &&
                old.curPage == new.curPage &&
                old.totalPage == new.totalPage &&
                old.position == new.position
