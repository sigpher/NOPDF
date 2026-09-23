package com.sigpher.nopdf.preview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sigpher.nopdf.R

/**
 * 目录（书签树）列表适配器，替代原先已停止维护的 AndroidTreeView。
 *
 * 交互与原实现保持一致：点击行首图标展开/折叠，点击整行跳转到对应页。
 *
 * @author aaronzzxup@gmail.com
 */
class ContentAdapter(
        private val onIconClick: (Int) -> Unit,
        private val onRowClick: (Int) -> Unit
) : RecyclerView.Adapter<ContentAdapter.RowHolder>() {

    private val rows: MutableList<ContentRow> = ArrayList()

    fun submit(list: List<ContentRow>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.app_recycler_item_content, parent, false)
        return RowHolder(itemView)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(rows[position])
    }

    inner class RowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon: ImageView = itemView.findViewById(R.id.app_iv_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.app_tv_title)
        private val tvPage: TextView = itemView.findViewById(R.id.app_tv_page)

        init {
            ivIcon.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) onIconClick(adapterPosition)
            }
            itemView.setOnClickListener {
                if (adapterPosition != RecyclerView.NO_POSITION) onRowClick(adapterPosition)
            }
        }

        fun bind(row: ContentRow) {
            val context = itemView.context
            val expanded = row.hasChildren && row.expanded
            ivIcon.setImageResource(if (row.hasChildren) {
                R.drawable.app_ic_can_down_white
            } else {
                R.drawable.app_ic_cannot_down_white
            })
            ivIcon.rotation = if (expanded) 90f else 0f
            tvTitle.text = row.title
            tvTitle.setTextColor(ContextCompat.getColor(context, if (expanded) {
                R.color.app_content_bookmark_accent
            } else {
                R.color.app_content_bookmark_primary
            }))
            tvPage.text = (row.pageIdx + 1).toString()
            // 与原 AndroidTreeView 的容器样式保持一致：每层累加 16dp 缩进
            val indent = context.resources.getDimensionPixelSize(R.dimen.app_content_indent) * (row.depth + 1)
            itemView.setPaddingRelative(indent, itemView.paddingTop, itemView.paddingEnd, itemView.paddingBottom)
        }
    }
}
