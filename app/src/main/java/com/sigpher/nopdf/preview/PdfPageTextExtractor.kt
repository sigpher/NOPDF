package com.sigpher.nopdf.preview

import android.content.Context
import android.util.Log

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

import java.io.File

/**
 * 用 PDFBox 提取单页 PDF 的字符框。
 *
 * 为什么要引入 PDFBox：pdfium-android 1.9.0 的 Java 层完全没有文本 API
 * （无 TextPage / loadTextPage / getTextBounded），而其自带的 libmodpdfium.so 虽导出了
 * FPDFText_* 系列符号，却没有任何 JNI 绑定，因此无法取到文字。要把「点到的位置」映射到
 * 「词」，必须有带坐标的文本，只能另起 PDFBox。
 *
 * 代价：
 * 1. 与 pdfium 重复解析同一份文件（pdfium 那份句柄由 PDFView 持有，无法复用）；
 * 2. 解析是耗时操作，必须在后台线程调用，并对结果按页缓存。
 *
 * 坐标系：与 [CharBox] 一致，原点左上、y 向下、单位 PDF 点。
 */
class PdfPageTextExtractor private constructor() {

    private val pageCache = LinkedHashMap<String, List<CharBox>>()

    /**
     * 取出第 [page] 页的字符框。
     *
     * @param page 0 基页码，与 PDFView.getCurrentPage() 一致
     * 结果按 (path, page, password) 缓存，重复点击同一页不会重复解析。
     * **必须在后台线程调用**：内部会重新解析整份 PDF。
     */
    @Synchronized
    fun pageChars(context: Context, path: String, page: Int, password: String?): List<CharBox> {
        val key = "$path#$page#$password"
        pageCache[key]?.let { return it }
        val chars = extract(context, path, page, password)
        // 只缓存最近若干页，避免长时间阅读后无上限增长。
        if (pageCache.size >= MAX_CACHED_PAGES) {
            val oldest = pageCache.keys.firstOrNull()
            if (oldest != null) {
                pageCache.remove(oldest)
            }
        }
        pageCache[key] = chars
        return chars
    }

    @Synchronized
    fun clear() {
        pageCache.clear()
    }

    private fun extract(context: Context, path: String, page: Int, password: String?): List<CharBox> {
        // PDFBox 的资源加载器必须先初始化一次（读取字体等内置资源）。
        // 注意：这一行在 try 之外——它一旦抛异常会直接冒泡出去，此处显式兜住并记录。
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, "PDFBoxResourceLoader.init failed", t)
            return emptyList()
        }
        var document: PDDocument? = null
        return try {
            // 用临时文件模式而不是默认的全内存模式：大 PDF 全内存会直接把应用 OOM 掉，
            // 而 OOM 是 catch 不住的（进程会被杀）。
            val usage = MemoryUsageSetting.setupTempFileOnly()
            document = if (password.isNullOrEmpty()) {
                PDDocument.load(File(path), usage)
            } else {
                PDDocument.load(File(path), password, usage)
            }
            if (page < 0 || page >= document.numberOfPages) {
                return emptyList()
            }
            val chars = ArrayList<CharBox>()
            val stripper = object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    // 扫描版 PDF 常见零宽/不可见字形，直接丢弃，否则会切出空词。
                    val unicode = text.unicode
                    if (unicode.isNullOrEmpty() || unicode == " ") {
                        return
                    }
                    chars.add(
                            CharBox(
                                    text.xDirAdj,
                                    text.yDirAdj,
                                    text.widthDirAdj,
                                    text.heightDir,
                                    unicode
                            )
                    )
                }
            }
            // setSortByPosition 让 processTextPosition 按阅读顺序回调，否则切词会乱序。
            stripper.sortByPosition = true
            stripper.addMoreFormatting = false
            // PDFBox 的页码从 1 开始，PDFView 的页码从 0 开始，这里做一次换算。
            stripper.startPage = page + 1
            stripper.endPage = page + 1
            // 逐字形已在 processTextPosition 中收集，忽略这里返回的整页文本。
            stripper.getText(document)
            Log.e(TAG, "extract ok: page=$page chars=${chars.size}")
            chars
        } catch (e: Throwable) {
            // 解析失败（加密、损坏、无文本层）都退化为「取不到词」，不能影响阅读。
            // 但仍要记录原因，否则「取不到词」无从区分是加密、损坏还是缺资源。
            Log.e(TAG, "extract failed: page=$page path=$path", e)
            emptyList()
        } finally {
            try {
                document?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    companion object {
        private const val MAX_CACHED_PAGES = 8

        /** 与 PreviewActivity 的选词查词共用同一个 logcat tag，便于一起抓取。 */
        private const val TAG = "PdfLookup"

        @Volatile
        private var instance: PdfPageTextExtractor? = null

        fun get(): PdfPageTextExtractor {
            return instance ?: synchronized(this) {
                instance ?: PdfPageTextExtractor().also { instance = it }
            }
        }
    }
}
