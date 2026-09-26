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
    // 三个量（视图像素、页面原点偏移）都已是「含缩放」的单位，必须先相减再除以 zoom。
    // 顺序写反（先除 zoom 再减原点）等于减了 origin/zoom，在 fitEachPage 的
    // zoom≈1.76 下偏差可达数百像素，表现为「怎么按都取不到词」。
    fun toPageX(viewX: Float): Float = (viewX + currentXOffset - pageOffsetX) / zoom

    fun toPageY(viewY: Float): Float = (viewY + currentYOffset - pageOffsetY) / zoom

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
    /**
     * [normalize] 只去掉这些 ASCII 标点，**不能**按「非字母」一刀切：
     * 否则 "café" 会被削成 "caf" 而通过英文判定，中文同理。首尾的引号/括号/
     * 句读要去掉，但撇号与连字符要留给词内部（don't、well-known）。
     */
    private val edgeTrim =
        setOf(' ', '\t', '\n', '\r', ',', ';', ':', '.', '!', '?', '"',
                '(', ')', '[', ']', '{', '}', '<', '>', '_', '*', '~', '`')

    /** 视图像素的手指容差：点击未必精确落在字形内，向上取最近的一个词。 */
    const val TAP_TOLERANCE_PX = 24f

    /**
     * 无空格字形时，按横向间隙断词的阈值（相对字高）。
     * 空格宽度约 0.25~0.33 em，而字高（cap height）约 0.7 em，故 0.28 倍字高
     * 落在「词内字距」与「词间空格」之间。
     */
    private const val SPACE_GAP_RATIO = 0.28f
    /** 间隙阈值下限，避免小字号时把词切断。 */
    private const val MIN_SPACE_GAP_PT = 1.2f

    /**
     * 把按阅读顺序排列的字符框切分成单词。
     *
     * 不能只按空白字符切：不少 PDF（尤其是用 `Td`/`TJ` 定位排版的）**根本没有空格字形**，
     * 词与词之间只是一个位移。此时纯空白切分会把整页连成一个超长「单词」，
     * 后续英文判定必然失败，表现为「按了取不到词」。因此还要按三种情况断词：
     * 1. 空白字符；
     * 2. 换行（下一字符的基线明显不同）；
     * 3. 横向间隙明显大于字间距（用字高估算一个空格宽度）。
     */
    fun words(chars: List<CharBox>): List<WordBox> {
        val result = ArrayList<WordBox>()
        var index = 0
        while (index < chars.size) {
            if (chars[index].text.isBlank()) {
                index++
                continue
            }
            var end = index + 1
            while (end < chars.size && !isWordBoundary(chars[end - 1], chars[end])) {
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

    /** [next] 是否应当与 [prev] 断开，另起一个单词。 */
    private fun isWordBoundary(prev: CharBox, next: CharBox): Boolean {
        if (next.text.isBlank()) {
            return true
        }
        val height = if (prev.height > 0f) prev.height else next.height
        // 换行：基线（y）明显不同
        if (height > 0f && abs(next.y - prev.y) > height * 0.5f) {
            return true
        }
        // 横向间隙超过一个估算的空格宽度
        val gap = next.x - prev.right
        return gap > max(MIN_SPACE_GAP_PT, height * SPACE_GAP_RATIO)
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
        // 统一用「点到字形包围盒的距离」判定：落在盒内距离为 0，近邻按距离排序，
        // 超过容差不命中。
        // 不要改成「容差放大盒后顺次取第一个」——放大盒彼此重叠，
        // 结果会取决于列表顺序，按住右边的词却取到左边的词。
        var best: WordBox? = null
        var bestDistance = Float.MAX_VALUE
        for (w in words) {
            val dx = distanceToRange(pageX, w.x, w.x + w.width)
            val dy = distanceToRange(pageY, w.y, w.y + w.height)
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = w
            }
        }
        return if (bestDistance <= tolerance) best else null
    }

    /** 点 [value] 到区间 [low, high] 的距离；落在区间内为 0。 */
    private fun distanceToRange(value: Float, low: Float, high: Float): Float {
        return when {
            value < low -> low - value
            value > high -> value - high
            else -> 0f
        }
    }

    /**
     * 去掉首尾的标点，得到真正用于查词的词。
     *
     * PDF 里切出来的词常带句读："language,"、"(word)"、"end."。
     * 若直接拿去做英文判定，逗号/括号会让 [isTranslatable] 整条拒绝，
     * 表现为「明明是英文却提示取不到词」。首尾的连字符/点也要去掉，
     * 但词内部的撇号与连字符保留（don't、well-known）。
     */
    fun normalize(word: String): String {
        var start = 0
        var end = word.length
        while (start < end && word[start] in edgeTrim) {
            start++
        }
        while (end > start && word[end - 1] in edgeTrim) {
            end--
        }
        return word.substring(start, end)
    }

    /**
     * 判断是否值得送去查词（会先去掉首尾标点）。
     *
     * 本功能只做「英文 → 中文」，因此放过不含任何 ASCII 字母的内容（纯数字、标点、页码），
     * 也不接受含中日韩字符的词。
     */
    fun isTranslatable(word: String): Boolean {
        val normalized = normalize(word)
        if (normalized.isEmpty() || normalized.length > MAX_WORD_LENGTH) {
            return false
        }
        var hasLetter = false
        for (ch in normalized) {
            when {
                ch in 'a'..'z' || ch in 'A'..'Z' -> hasLetter = true
                ch in allowedInWord -> Unit
                else -> return false
            }
        }
        return hasLetter
    }
}
