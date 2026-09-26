package com.sigpher.nopdf.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.Switch
import androidx.recyclerview.widget.RecyclerView
import com.sigpher.nopdf.R
import com.sigpher.nopdf.common.App
import com.sigpher.nopdf.common.Settings
import com.sigpher.nopdf.common.event.MaxRecentEvent
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ConvertUtils
import kotlinx.android.synthetic.main.app_recycler_item_settings_recent_count.view.*
import kotlinx.android.synthetic.main.app_recycler_item_settings_seekbar.view.*
import kotlinx.android.synthetic.main.app_recycler_item_settings_switch.view.*
// app_tv_title / app_tv_count / app_spinner 在 switch、recent_count、page_spacing 三个 item 布局里
// 刻意复用同名 id：这样 MaxRecentHolder 与 PageSpacingHolder 能共用一段绑定逻辑。代价是星号
// 导入这些名字时会产生重载歧义，必须用显式导入（优先级高于星号）把它定到其中一个布局。
// 合成视图最终都是对同一个 id 做 findViewById，且这几个 id 在各布局里类型一致，定到哪个都一样，
// 因此这里不再导入 page_spacing，直接用 recent_count 的同名合成属性即可。
import kotlinx.android.synthetic.main.app_recycler_item_settings_switch.view.app_tv_title
import org.greenrobot.eventbus.EventBus

/**
 * @author Aaron aaronzzxup@gmail.com
 */
internal class SettingsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val maxRecentEvent: MaxRecentEvent = MaxRecentEvent()
    private val maxRecentCounts: List<String>
    private val pageSpacings: List<String>

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val inflater = LayoutInflater.from(context)
        if (viewType == TYPE_SWITCH) {
            val itemView = inflater.inflate(R.layout.app_recycler_item_settings_switch, parent, false)
            val holder = SwitchHolder(itemView)
            holder.itemView.setOnClickListener {
                val pos = holder.adapterPosition
                holder.itemView.app_switch.isChecked = !holder.itemView.app_switch.isChecked
                tapOptions(holder.itemView.app_switch, pos)
            }
            return holder
        } else if (viewType == TYPE_NUM_PICKER) {
            val itemView = inflater.inflate(R.layout.app_recycler_item_settings_recent_count, parent, false)
            val holder = MaxRecentHolder(itemView)
            holder.itemView.setOnClickListener { holder.itemView.app_spinner.performClick() }
            holder.itemView.app_spinner.dropDownHorizontalOffset = -ConvertUtils.dp2px(46f)
            holder.itemView.app_spinner.onItemSelectedListener = object : OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                    val maxRecentCount = maxRecentCounts[position]
                    holder.itemView.app_tv_count.text = maxRecentCount
                    Settings.maxRecentCount = maxRecentCount
                    EventBus.getDefault().post(maxRecentEvent)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            return holder
        } else if (viewType == TYPE_PAGE_SPACING) {
            val itemView = inflater.inflate(R.layout.app_recycler_item_settings_page_spacing, parent, false)
            val holder = PageSpacingHolder(itemView)
            holder.itemView.setOnClickListener { holder.itemView.app_spinner.performClick() }
            holder.itemView.app_spinner.dropDownHorizontalOffset = -ConvertUtils.dp2px(46f)
            holder.itemView.app_spinner.onItemSelectedListener = object : OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                    val spacing = pageSpacings.getOrNull(position)?.toIntOrNull() ?: return
                    Settings.pageSpacing = spacing
                    holder.itemView.app_tv_count.text =
                            context.getString(R.string.app_page_spacing_value, spacing)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            return holder
        }
        val itemView = inflater.inflate(R.layout.app_recycler_item_settings_seekbar, parent, false)
        val holder = SeekbarHolder(itemView)
        holder.itemView.app_sb_scroll_level.max = 14
        holder.itemView.app_sb_scroll_level.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val level = seekBar.progress + 1
//                UiManager.showShort(context.getString(R.string.app_cur_level, level))
                Settings.scrollLevel = level.toLong()
            }
        })
        return holder
    }

    private fun tapOptions(switcher: Switch, pos: Int) {
        when (pos) {
            POS_VOLUME_CONTROL -> Settings.volumeControl = switcher.isChecked
            POS_CLICK_FLIP_PAGE -> Settings.clickFlipPage = switcher.isChecked
            POS_SHOW_STATUS_BAR -> Settings.showStatusBar = switcher.isChecked
            POS_KEEP_SCREEN_ON -> Settings.keepScreenOn = switcher.isChecked
            POS_LINEAR_LAYOUT -> Settings.linearLayout = switcher.isChecked
            POS_SCROLL_SHORTCUT -> Settings.scrollShortCut = switcher.isChecked
            POS_GLOBAL_GREY -> {
                Settings.globalGrey = switcher.isChecked
                switcher.postDelayed({ AppUtils.relaunchApp(true) }, 500L)
            }
            POS_HIDE_SCROLL_LEVEL_BAR -> Settings.hideScrollLevelBar = switcher.isChecked
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        when (viewHolder) {
            is SwitchHolder -> {
                when (position) {
                    POS_VOLUME_CONTROL -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_volume_control)
                        viewHolder.itemView.app_switch.isChecked = Settings.volumeControl
                    }
                    POS_CLICK_FLIP_PAGE -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_click_flip_page)
                        viewHolder.itemView.app_switch.isChecked = Settings.clickFlipPage
                    }
                    POS_SHOW_STATUS_BAR -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_show_status_bar)
                        viewHolder.itemView.app_switch.isChecked = Settings.showStatusBar
                    }
                    POS_KEEP_SCREEN_ON -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_keep_screen_on)
                        viewHolder.itemView.app_switch.isChecked = Settings.keepScreenOn
                    }
                    POS_LINEAR_LAYOUT -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_linear_layout)
                        viewHolder.itemView.app_switch.isChecked = Settings.linearLayout
                    }
                    POS_SCROLL_SHORTCUT -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_scroll_shortcut)
                        viewHolder.itemView.app_switch.isChecked = Settings.scrollShortCut
                    }
                    POS_GLOBAL_GREY -> {
                        if ((viewHolder.itemView.context as SettingsActivity).fromPreview) {
                            viewHolder.itemView.isEnabled = false
                        }
                        viewHolder.itemView.app_tv_title.setText(R.string.app_global_grey)
                        viewHolder.itemView.app_switch.isChecked = Settings.globalGrey
                    }
                    POS_HIDE_SCROLL_LEVEL_BAR -> {
                        viewHolder.itemView.app_tv_title.setText(R.string.app_hide_scroll_level_bar)
                        viewHolder.itemView.app_switch.isChecked = Settings.hideScrollLevelBar
                    }
                }
            }
            is SeekbarHolder -> {
                viewHolder.itemView.app_tv_title.setText(R.string.app_scroll_velocity)
                viewHolder.itemView.app_sb_scroll_level.progress = Settings.scrollLevel.toInt() - 1
            }
            is MaxRecentHolder -> {
                viewHolder.itemView.app_tv_title.setText(R.string.app_max_recent_count)
                viewHolder.itemView.app_tv_count.text = Settings.maxRecentCount
                viewHolder.itemView.app_spinner.setSelection(maxRecentCounts.indexOf(Settings.maxRecentCount))
            }
            is PageSpacingHolder -> {
                viewHolder.itemView.app_tv_title.setText(R.string.app_page_spacing)
                val spacing = Settings.pageSpacing
                viewHolder.itemView.app_tv_count.text =
                        viewHolder.itemView.context.getString(R.string.app_page_spacing_value, spacing)
                // 旧版本可能存过不在候选表里的值，indexOf 为 -1 时退回第一项，避免 Spinner 空白。
                viewHolder.itemView.app_spinner.setSelection(
                        pageSpacings.indexOf(spacing.toString()).coerceAtLeast(0))
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (position == POS_SCROLL_LEVEL) {
            return TYPE_SEEKBAR
        } else if (position == POS_NUM_PICKER) {
            return TYPE_NUM_PICKER
        } else if (position == POS_PAGE_SPACING) {
            return TYPE_PAGE_SPACING
        }
        return TYPE_SWITCH
    }

    override fun getItemCount(): Int {
        return ITEM_COUNT
    }

    internal class SwitchHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            val mlp = itemView.layoutParams as MarginLayoutParams
            mlp.bottomMargin = ConvertUtils.dp2px(0.6f)
            itemView.layoutParams = mlp
        }
    }

    internal class SeekbarHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            val mlp = itemView.layoutParams as MarginLayoutParams
            mlp.topMargin = ConvertUtils.dp2px(0.6f)
            itemView.layoutParams = mlp
        }
    }

    internal class MaxRecentHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            val mlp = itemView.layoutParams as MarginLayoutParams
            mlp.topMargin = ConvertUtils.dp2px(12f)
            itemView.layoutParams = mlp
        }
    }

    internal class PageSpacingHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            val mlp = itemView.layoutParams as MarginLayoutParams
            mlp.topMargin = ConvertUtils.dp2px(0.6f)
            itemView.layoutParams = mlp
        }
    }

    companion object {
        private const val ITEM_COUNT = 11

        private const val TYPE_SWITCH = 0
        private const val TYPE_SEEKBAR = 1
        private const val TYPE_NUM_PICKER = 2
        private const val TYPE_PAGE_SPACING = 3

        private const val POS_VOLUME_CONTROL = 0
        private const val POS_CLICK_FLIP_PAGE = 1
        private const val POS_SHOW_STATUS_BAR = 2
        private const val POS_KEEP_SCREEN_ON = 3
        private const val POS_LINEAR_LAYOUT = 4
        private const val POS_SCROLL_SHORTCUT = 5
        private const val POS_GLOBAL_GREY = 6
        private const val POS_HIDE_SCROLL_LEVEL_BAR = 7
        private const val POS_NUM_PICKER = 8
        private const val POS_SCROLL_LEVEL = 9
        private const val POS_PAGE_SPACING = 10
    }

    init {
        val array = App.getContext().resources.getStringArray(R.array.max_recent_count)
        maxRecentCounts = listOf(*array)
        val spacingArray = App.getContext().resources.getStringArray(R.array.page_spacing)
        pageSpacings = listOf(*spacingArray)
    }
}