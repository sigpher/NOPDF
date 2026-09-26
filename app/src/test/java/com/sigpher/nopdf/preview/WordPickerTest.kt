package com.sigpher.nopdf.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选词查词纯逻辑的回归测试（纯 JVM，不依赖 Android 与 PDFBox）。
 *
 * 坐标系约定：原点在页面左上角、y 轴向下、单位 PDF 点，与 [CharBox] 一致。
 *
 * @author aaronzzxup@gmail.com
 */
class WordPickerTest {

    /** 构造一行等宽字符，x 每字递增 [advance]，用于快速铺出一行文本。 */
    private fun line(text: String, startX: Float, y: Float, advance: Float = 10f): List<CharBox> {
        return text.mapIndexed { index, ch ->
            CharBox(startX + index * advance, y, advance, 12f, ch.toString())
        }
    }

    private fun wordsOf(text: String) = WordPicker.words(line(text, 0f, 100f))

    // ---------- words：切词 ----------

    @Test
    fun `按空格切分单词并忽略空白`() {
        val words = wordsOf("hello world")

        assertEquals(listOf("hello", "world"), words.map { it.text })
    }

    @Test
    fun `单词包围盒覆盖其全部字符`() {
        val words = wordsOf("cat")

        val box = words.single()
        assertEquals(0f, box.x, EPS)
        assertEquals(100f, box.y, EPS)
        assertEquals(30f, box.width, EPS)   // 3 个字符 * 10
        assertEquals(12f, box.height, EPS)
    }

    @Test
    fun `词内撇号与连字符不被切断`() {
        val words = wordsOf("don't well-known")

        assertEquals(listOf("don't", "well-known"), words.map { it.text })
    }

    @Test
    fun `连续多个空格只产生一个分隔`() {
        val words = wordsOf("a  b")

        assertEquals(listOf("a", "b"), words.map { it.text })
    }

    @Test
    fun `无空格字形时按横向间隙断词`() {
        // 真实回归：很多 PDF 用 Td/TJ 定位排版，词与词之间没有空格字形。
        // 旧实现会把整行连成一个词，导致英文判定失败、永远取不到词。
        val chars = listOf(
                CharBox(72f, 92f, 13f, 14f, "a"),
                CharBox(85f, 92f, 13f, 14f, "l"),
                CharBox(98f, 92f, 13f, 14f, "p"),
                // 与上一个字符有 59pt 间隙（远超 0.28*14≈3.9pt 的阈值）→ 断词
                CharBox(170f, 92f, 13f, 14f, "b"),
                CharBox(183f, 92f, 13f, 14f, "e")
        )
        assertEquals(listOf("alp", "be"), WordPicker.words(chars).map { it.text })
    }

    @Test
    fun `换行处断词`() {
        val chars = listOf(
                CharBox(72f, 92f, 13f, 14f, "a"),
                CharBox(85f, 92f, 13f, 14f, "b"),
                // y 变化 100pt > 0.5*14 → 视为换行
                CharBox(72f, 192f, 13f, 14f, "c")
        )
        assertEquals(listOf("ab", "c"), WordPicker.words(chars).map { it.text })
    }

    @Test
    fun `词内正常字距不会误断`() {
        // 间隙 0pt，Helvetica 24pt 下正常
        val chars = listOf(
                CharBox(72f, 92f, 13.3f, 13.9f, "H"),
                CharBox(85.3f, 92f, 13.3f, 13.9f, "e"),
                CharBox(98.6f, 92f, 13.3f, 13.9f, "l")
        )
        assertEquals(listOf("Hel"), WordPicker.words(chars).map { it.text })
    }

    @Test
    fun `空输入不产生单词`() {
        assertTrue(WordPicker.words(emptyList()).isEmpty())
    }

    @Test
    fun `整页空白不产生单词`() {
        assertTrue(WordPicker.words(line("   ", 0f, 0f)).isEmpty())
    }

    // ---------- wordAt：命中测试 ----------

    @Test
    fun `命中点击所在单词`() {
        val words = wordsOf("alpha beta gamma")

        // "beta" 位于 x=60..100, y=100..112
        val hit = WordPicker.wordAt(words, 70f, 105f, tolerance = 0f)

        assertEquals("beta", hit?.text)
    }

    @Test
    fun `超出所有词时返回 null`() {
        val words = wordsOf("alpha beta")

        assertNull(WordPicker.wordAt(words, 500f, 500f, tolerance = 2f))
    }

    @Test
    fun `容差内可命中附近的词`() {
        val words = wordsOf("alpha beta")

        // "alpha" 包围盒 x=0..50，"beta" 从 x=60 开始；x=52 落在两者之间的空隙，
        // 容差 8 时应就近命中 "alpha"。
        val hit = WordPicker.wordAt(words, 52f, 106f, tolerance = 8f)

        assertEquals("alpha", hit?.text)
    }

    @Test
    fun `容差不足时不命中`() {
        val words = wordsOf("alpha beta")

        // 同一点，容差收到 1 时既不落在任何包围盒内，也够不到最近词的中心。
        assertNull(WordPicker.wordAt(words, 52f, 106f, tolerance = 1f))
    }

    @Test
    fun `容差放大盒重叠时优先取严格命中的词`() {
        val words = wordsOf("alpha beta")
        // "alpha" x=0..50, "beta" x=60..110。x=70 严格落在 beta 内，
        // 但 alpha 的容差放大盒（-8..58+8）在 x=58 处已越界到 beta 之前，
        // 旧实现会先匹配 alpha。
        val hit = WordPicker.wordAt(words, 70f, 106f, tolerance = 8f)

        assertEquals("beta", hit?.text)
    }

    @Test
    fun `空词表恒不命中`() {
        assertNull(WordPicker.wordAt(emptyList(), 10f, 10f, tolerance = 100f))
    }

    // ---------- isTranslatable：只收英文 ----------

    @Test
    fun `接受普通英文单词`() {
        assertTrue(WordPicker.isTranslatable("hello"))
        assertTrue(WordPicker.isTranslatable("Hello"))
    }

    @Test
    fun `接受带撇号连字符与缩写的专有名词`() {
        assertTrue(WordPicker.isTranslatable("don't"))
        assertTrue(WordPicker.isTranslatable("well-known"))
        assertTrue(WordPicker.isTranslatable("U.S.A."))
        assertTrue(WordPicker.isTranslatable("John Smith"))
    }

    @Test
    fun `拒绝不含字母的内容`() {
        assertFalse(WordPicker.isTranslatable("123"))
        assertFalse(WordPicker.isTranslatable("---"))
        assertFalse(WordPicker.isTranslatable(""))
    }

    @Test
    fun `拒绝中文与非拉丁文字`() {
        assertFalse(WordPicker.isTranslatable("词典"))
        assertFalse(WordPicker.isTranslatable("hello词典"))
        assertFalse(WordPicker.isTranslatable("café"))
    }

    @Test
    fun `去掉首尾标点后仍可查词`() {
        // 回归：曾经直接用带标点的词做英文判定，逗号/括号会让整条被拒
        assertEquals("language", WordPicker.normalize("language,"))
        assertEquals("word", WordPicker.normalize("(word)"))
        assertEquals("end", WordPicker.normalize("end."))
        assertEquals("don't", WordPicker.normalize("don't"))
        assertEquals("U.S.A", WordPicker.normalize("U.S.A."))
        assertTrue(WordPicker.isTranslatable("language,"))
        assertTrue(WordPicker.isTranslatable("(word)"))
    }

    @Test
    fun `只有标点没有字母时归一化为空`() {
        assertEquals("", WordPicker.normalize("..."))
        assertEquals("", WordPicker.normalize(",;:")) 
        assertFalse(WordPicker.isTranslatable("..."))
    }

    @Test
    fun `拒绝过长的整句`() {
        assertFalse(WordPicker.isTranslatable("a".repeat(WordPicker.MAX_WORD_LENGTH + 1)))
        assertTrue(WordPicker.isTranslatable("a".repeat(WordPicker.MAX_WORD_LENGTH)))
    }

    // ---------- PageTransform：坐标换算 ----------

    @Test
    fun `缩放为一时偏移即为页面坐标`() {
        val t = PageTransform(
                zoom = 1f,
                currentXOffset = 0f,
                currentYOffset = 0f,
                pageOffsetX = 0f,
                pageOffsetY = 0f
        )

        assertEquals(70f, t.toPageX(70f), EPS)
        assertEquals(105f, t.toPageY(105f), EPS)
    }

    @Test
    fun `页面原点偏移会从视图像素中扣除`() {
        val t = PageTransform(
                zoom = 1f,
                currentXOffset = 0f,
                currentYOffset = 0f,
                pageOffsetX = 20f,
                pageOffsetY = 200f
        )

        assertEquals(50f, t.toPageX(70f), EPS)
        assertEquals(-95f, t.toPageY(105f), EPS)
    }

    @Test
    fun `视口偏移与缩放同时参与换算`() {
        val t = PageTransform(
                zoom = 2f,
                currentXOffset = 100f,
                currentYOffset = 50f,
                pageOffsetX = 40f,
                pageOffsetY = 60f
        )

        // view = currentOffset + pageOrigin + 页面点 × zoom，逆变换先减两个偏移再除：
        // (70 - 100 - 40) / 2 = -35
        assertEquals(-35f, t.toPageX(70f), EPS)
        // (105 - 50 - 60) / 2 = -2.5
        assertEquals(-2.5f, t.toPageY(105f), EPS)
    }

    @Test
    fun `翻到非首页后页顶对齐仍能命中`() {
        // 真实场景：竖向滚动 + fitEachPage，zoom≈1.76。PDFView.jumpTo 会把视口偏移设为
        // currentOffset = -pageOrigin，使页顶对齐视口顶部（PDFView.java:301）。
        // 正确换算应退化为 viewY / zoom；若把 currentOffset 的符号写成加号，
        // 会得到 (viewY - 2*pageOrigin) / zoom，在非首页时彻底打偏、永远取不到词。
        // 第 0 页 pageOrigin = currentOffset = 0，符号写错也看不出来，故必须用非首页回归。
        val zoom = 1.76f
        val pageOriginY = 3000f
        val t = PageTransform(
                zoom = zoom,
                currentXOffset = 0f,
                currentYOffset = -pageOriginY,
                pageOffsetX = 0f,
                pageOffsetY = pageOriginY
        )

        assertEquals(500f / zoom, t.toPageY(500f), EPS)
        // 符号写反时 y 会是负几千点，这里再兜一层，防止断言被误改。
        assertTrue(t.toPageY(500f) > 0f)
    }

    @Test
    fun `先除缩放再减原点会得到错误结果`() {
        // 回归：曾经把公式写成 (view + offset) / zoom - pageOffset，
        // 等价于减了 pageOffset/zoom，在 zoom!=1 时偏差巨大。
        val t = PageTransform(
                zoom = 2f,
                currentXOffset = 0f,
                currentYOffset = 0f,
                pageOffsetX = 200f,
                pageOffsetY = 0f
        )
        // 正确：(500 - 200) / 2 = 150；错误：(500 / 2) - 200 = 50
        assertEquals(150f, t.toPageX(500f), EPS)
    }

    @Test
    fun `视图像素长度按缩放换算为页面长度`() {
        val t = PageTransform(2f, 0f, 0f, 0f, 0f)

        assertEquals(12f, t.viewToPageLength(24f), EPS)
    }

    @Test
    fun `坐标换算后能命中正确的词`() {
        // 页面内容 line("alpha beta", startX=40, y=10, advance=10)：
        // "alpha" 包围盒 x=40..90，"beta" 包围盒 x=100..150，中心 (125, 16)。
        // 取 zoom=2、视口与页面原点均为 0，则 toPage(v) = v / 2，
        // 于是视口 (250, 32) 恰好落在 "beta" 的中心。
        val t = PageTransform(zoom = 2f, currentXOffset = 0f, currentYOffset = 0f,
                pageOffsetX = 0f, pageOffsetY = 0f)
        val words = WordPicker.words(line("alpha beta", 40f, 10f, advance = 10f))

        val hit = WordPicker.wordAt(
                words,
                t.toPageX(250f),
                t.toPageY(32f),
                tolerance = 0f
        )

        assertEquals("beta", hit?.text)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
