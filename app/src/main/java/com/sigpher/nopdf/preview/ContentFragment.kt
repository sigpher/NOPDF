package com.sigpher.nopdf.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.sigpher.nopdf.R
import com.sigpher.nopdf.common.App
import com.sigpher.nopdf.common.CommonFragment
import com.shockwave.pdfium.PdfDocument
import kotlinx.android.synthetic.main.app_fragment_content.*
import kotlinx.android.synthetic.main.app_recycler_item_emptyview.*
import java.util.*

/**
 * 阅读页的「目录」侧边栏。原先基于已停止维护的 AndroidTreeView，
 * 现改为「书签树 -> 扁平行列表」+ RecyclerView，逻辑见 [ContentTree]。
 *
 * @author Aaron aaronzzxup@gmail.com
 */
class ContentFragment : CommonFragment(), IContentFragInterface {

    private val contentList: MutableList<PdfDocument.Bookmark> = ArrayList()
    private var tree: ContentTree? = null
    private var contentAdapter: ContentAdapter? = null

    override fun update(collection: MutableCollection<PdfDocument.Bookmark>) {
        contentList.clear()
        contentList.addAll(collection)
        renderContent()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.app_fragment_content, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app_itv_placeholder.setText(R.string.app_have_no_content)
        app_itv_placeholder.setTextColor(App.getContext().resources.getColor(R.color.app_content_bookmark_primary))
        app_itv_placeholder.setIconTop(R.drawable.app_ic_action_content_white)
        app_itv_placeholder.alpha = 0.6f

        contentAdapter = ContentAdapter(
                onIconClick = { position -> toggleNode(position) },
                onRowClick = { position -> jumpTo(position) }
        )
        app_rv_content.layoutManager = LinearLayoutManager(activity)
        app_rv_content.adapter = contentAdapter

        // 书签可能在视图创建前就已送达，这里补渲染一次
        renderContent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        contentAdapter = null
        tree = null
    }

    private fun toggleNode(position: Int) {
        val tree = tree ?: return
        if (tree.toggle(position)) {
            contentAdapter?.submit(tree.rows())
        }
    }

    private fun jumpTo(position: Int) {
        val row = tree?.rows()?.getOrNull(position) ?: return
        (activity as IActivityInterface).onJumpTo(row.pageIdx)
    }

    private fun renderContent() {
        val adapter = contentAdapter ?: return // 视图尚未创建，等 onViewCreated 再渲染
        if (contentList.isEmpty()) {
            tree = null
            app_itv_placeholder.visibility = View.VISIBLE
            adapter.submit(emptyList())
            return
        }
        app_itv_placeholder.visibility = View.GONE
        val built = ContentTree(contentList.map { it.toContentNode() })
        tree = built
        adapter.submit(built.rows())
    }

    private fun PdfDocument.Bookmark.toContentNode(): ContentNode {
        return ContentNode(
                title ?: "",
                pageIdx.toInt(),
                children?.map { it.toContentNode() } ?: emptyList()
        )
    }

    companion object {
        @JvmStatic
        fun newInstance(): Fragment {
            return ContentFragment()
        }
    }
}
