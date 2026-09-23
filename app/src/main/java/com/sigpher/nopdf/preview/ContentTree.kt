package com.sigpher.nopdf.preview

/**
 * 目录（PDF 书签树）的纯数据模型与展开/折叠逻辑。
 *
 * 这里刻意不依赖任何 Android API，也不依赖 pdfium 的 [com.shockwave.pdfium.PdfDocument.Bookmark]，
 * 因此可以直接用 JVM 单元测试覆盖（见 ContentTreeTest）。
 *
 * @author aaronzzxup@gmail.com
 */
data class ContentNode(
        val title: String,
        val pageIdx: Int,
        val children: List<ContentNode> = emptyList()
) {
    val hasChildren: Boolean
        get() = children.isNotEmpty()
}

/**
 * 目录树展开后的一行。
 *
 * @param id        节点在其树中的稳定路径标识，仅用于记录展开状态
 * @param depth     层级，顶层为 0
 * @param expanded  是否处于展开状态（叶子节点恒为 false）
 */
data class ContentRow(
        val id: String,
        val title: String,
        val pageIdx: Int,
        val depth: Int,
        val hasChildren: Boolean,
        val expanded: Boolean
)

/**
 * 把书签树摊平成可被 RecyclerView 直接渲染的行列表，并维护展开状态。
 */
class ContentTree(private val roots: List<ContentNode>) {

    private val expandedIds: MutableSet<String> = HashSet()
    private var cachedRows: List<ContentRow> = emptyList()

    /** 当前需要展示的行（只包含可见节点，按深度优先顺序）。 */
    fun rows(): List<ContentRow> {
        val result = ArrayList<ContentRow>()
        collect(roots, 0, "", result)
        cachedRows = result
        return result
    }

    /**
     * 切换第 [position] 行的展开状态。
     *
     * @return 状态是否发生变化；叶子节点或越界位置返回 false
     */
    fun toggle(position: Int): Boolean {
        val row = rows().getOrNull(position) ?: return false
        if (!row.hasChildren) return false
        if (!expandedIds.remove(row.id)) {
            expandedIds.add(row.id)
        }
        return true
    }

    private fun collect(nodes: List<ContentNode>, depth: Int, path: String, out: MutableList<ContentRow>) {
        for (index in nodes.indices) {
            val node = nodes[index]
            val id = if (path.isEmpty()) index.toString() else "$path.$index"
            val expanded = id in expandedIds
            out.add(ContentRow(id, node.title, node.pageIdx, depth, node.hasChildren, expanded))
            if (expanded) {
                collect(node.children, depth + 1, id, out)
            }
        }
    }
}
