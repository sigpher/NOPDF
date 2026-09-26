package com.sigpher.nopdf.preview

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 单个字符在 PDF 页面坐标系中的位置。
 *
 * 坐标系与 PDFBox 的 TextPosition 对齐：原点在页面左上角、y 轴向下、单位为 PDF 点（1/72 英寸）。
 * 这里只做纯数值运算，不触碰任何 Android / pdfium API，因此可由 JVM 单元测试覆盖（见 WordPickerTest）。
 */
data class CharBox(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val text: String
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * 一个完整的单词在页面坐标系中的包围盒。
 */
data class WordBox(
        val text: String,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}

/**
 * 从 PDFView 的视图像素坐标系换算到 PDF 页面坐标系所需的全部参数。
 *
 * 之所以要把这几个值显式收集齐，是因为页面在文档条带中的原点并不是 [pageOffsetX] 单独决定的：
 * 竖向滚动时主轴是 Y（[pageOffsetX] 实际取的是居中偏移），横向滚动时主轴是 X。
 * 这些语义都封装在 PdfFile 里，调用方只需把两个偏移按正确的轴填进来。
 *
 * @param zoom             PDFView 当前缩放（PDF 点 → 视图像素的倍数）
 * @param currentXOffset   视口左上角在文档条带中的 X（视图像素，已含缩放）
 * @param currentYOffset   视口左上角在文档条带中的 Y（视图像素，已含缩放）
 * @param pageOffsetX      当前页在文档条带中的原点 X（视图像素，已含缩放）
 * @param pageOffsetY      当前页在文档条带中的原点 Y（视图像素，已含缩放）
 */
data class PageTransform(
        val zoom: Float,
        val currentXOffset: Float,
        val currentYOffset: Float,
        val pageOffsetX: Float,
        val pageOffsetY: Float
) {
    fun toPageX(viewX: Float): Float = (viewX + currentXOffset) / zoom - pageOffsetX

    fun toPageY(viewY: Float): Float = (viewY + currentYOffset) / zoom - pageOffsetY

    /** 视图像素长度换算为页面点，用于把手指容差换算到页面坐标系。 */
    fun viewToPageLength(viewLength: Float): Float = viewLength / zoom
}

/**
 * 选词查词的纯逻辑：把 PDFBox 提取出的字符框切分成单词，并按点击位置做命中测试。
 *
 * 刻意不依赖 Android 与 PDFBox，只依赖上面两个纯数据类，因此可以完全用 JVM 单元测试覆盖。
 */
object WordPicker {

    /** 单词允许的最大字符数，超出的多半是整句而不是词。 */
    const val MAX_WORD_LENGTH = 40

    /** 允许出现在英文单词内部的字符：撇号、连字符、点（U.S.A.）、空格（人名 "John Smith"）。 */
    private val allowedInWord = " '-.".toSet()

    /** 视图像素的手指容差：点击未必精确落在字形内，向上取最近的一个词。 */
    const val TAP_TOLERANCE_PX = 24f

    /**
     * 把按阅读顺序排列的字符框切分成单词。
     *
     * PDFBox 逐字形回调，词与词之间没有显式边界，这里以空白字符切分，并把连续的非空白字符
     * 归为一个词。空白字符自身不产生 [WordBox]。
     */
    fun words(chars: List<CharBox>): List<WordBox> {
        val result = ArrayList<WordBox>()
        var index = 0
        while (index < chars.size) {
            if (chars[index].text.isBlank()) {
                index++
                continue
            }
            var end = index
            while (end < chars.size && chars[end].text.isNotBlank()) {
                end++
            }
            val slice = chars.subList(index, end)
            val text = buildString {
                for (c in slice) {
                    if (c.text.isNotBlank()) append(c.text.trim())
                }
            }
            if (text.isNotEmpty()) {
                var left = Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                for (c in slice) {
                    left = min(left, c.x)
                    top = min(top, c.y)
                    right = max(right, c.right)
                    bottom = max(bottom, c.bottom)
                }
                result.add(WordBox(text, left, top, right - left, bottom - top))
            }
            index = end
        }
        return result
    }

    /**
     * 找出被点击的那个词。
     *
     * 先找完全命中字形包围盒的词；没有命中时（手指较粗、字形有斜体伸出等）退化为在
     * [tolerance]（页面坐标系下的长度）内找中心点最近的词。
     *
     * @return 命中的词；没有命中返回 null
     */
    fun wordAt(words: List<WordBox>, pageX: Float, pageY: Float, tolerance: Float): WordBox? {
        for (w in words) {
            val hitX = pageX >= w.x - tolerance && pageX <= w.x + w.width + tolerance
            val hitY = pageY >= w.y - tolerance && pageY <= w.y + w.height + tolerance
            if (hitX && hitY) {
                return w
            }
        }
        var best: WordBox? = null
        var bestDistance = Float.MAX_VALUE
        for (w in words) {
            val dx = pageX - w.centerX
            val dy = pageY - w.centerY
            val distance = abs(dx) + abs(dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = w
            }
        }
        return if (bestDistance <= tolerance) best else null
    }

    /**
     * 判断是否值得送去查词。
     *
     * 本功能只做「英文 → 中文」，因此放过不含任何 ASCII 字母的内容（纯数字、标点、页码），
     * 也不接受含中日韩字符的词。
     */
    fun isTranslatable(word: String): Boolean {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) {
            return false
        }
        var hasLetter = false
        for (ch in word) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> hasLetter = true
                ch in allowedInWord -> Unit
                else -> return false
            }
        }
        return hasLetter
    }
}
