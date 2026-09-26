package com.github.barteksc.pdfviewer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块渲染的坐标换算。
 *
 * 这一层曾是纯 bug 温床且没有任何测试覆盖：`RenderingHandler` 过去在本地把「页相对比例」
 * 交给矩阵换算成页空间矩形，但它手里只有分块位图自己的像素尺寸、没有页面尺寸，换出来的
 * 矩形约等于整页大小，于是 MuPDF 把整页画进了每一块，`PDFView.drawPart` 再把每一块拉伸到
 * 它自己那 1/cols × 1/rows 的格子里——最终显示为一堆重复的小页面。这里把换算钉死。
 */
class PageRegionTest {

    private val eps = 1e-3f

    /** 缩放平移必须把该区域**恰好**铺满位图，而不是覆盖住它。 */
    private fun assertCoversExactly(region: PageRegion.Region, bitmapWidth: Int, bitmapHeight: Int) {
        val t = PageRegion.deviceTransform(region, bitmapWidth, bitmapHeight)
        val scaleX = t[0]
        val scaleY = t[1]
        val offsetX = t[2]
        val offsetY = t[3]

        // 区域左上角落到设备原点
        assertEquals(0f, region.left * scaleX + offsetX, eps)
        assertEquals(0f, region.top * scaleY + offsetY, eps)
        // 区域右下角落到位图右下角——不是更靠里（少画），也不是更靠外（多画）
        assertEquals(bitmapWidth.toFloat(), region.right * scaleX + offsetX, eps)
        assertEquals(bitmapHeight.toFloat(), region.bottom * scaleY + offsetY, eps)
    }

    @Test
    fun `整页相对矩形换算成整页`() {
        val region = PageRegion.of(0f, 0f, 1f, 1f, 595, 842)
        assertEquals(0f, region.left, eps)
        assertEquals(0f, region.top, eps)
        assertEquals(595f, region.right, eps)
        assertEquals(842f, region.bottom, eps)
    }

    @Test
    fun `分块换算到该块在页内的实际区域`() {
        // A4 页面按 PagesLoader 的算法切成 3 列 4 行，取第 1 行第 2 列
        val cols = 3
        val rows = 4
        val relW = 1f / cols
        val relH = 1f / rows
        val region = PageRegion.of(relW, 0f, relW * 2f, relH, 595, 842)

        assertEquals(595f / cols, region.left, eps)
        assertEquals(0f, region.top, eps)
        assertEquals(595f / cols * 2f, region.right, eps)
        assertEquals(842f / rows, region.bottom, eps)
        assertTrue("区域必须小于整页", region.width() < 595f)
    }

    @Test
    fun `分块恰好铺满正方形位图`() {
        val cols = 3
        val rows = 4
        val region = PageRegion.of(1f / cols, 0f, 2f / cols, 1f / rows, 595, 842)
        // 块是正方形位图，但该块在页内的切片不是正方形——两轴必须各自缩放
        assertCoversExactly(region, 256, 256)
    }

    @Test
    fun `缩略图恰好铺满位图且两轴等比`() {
        val region = PageRegion.of(0f, 0f, 1f, 1f, 595, 842)
        assertCoversExactly(region, 179, 253)
        // 缩略图位图按页面尺寸乘 THUMBNAIL_RATIO 生成，两轴缩放必须一致（不畸变）
        val t = PageRegion.deviceTransform(region, Math.round(595 * 0.3f), Math.round(842 * 0.3f))
        assertEquals(t[0], t[1], 0.01f)
    }

    @Test
    fun `相邻两块不重叠不留缝`() {
        val cols = 3
        val rows = 4
        val pw = 595
        val ph = 842
        val left = PageRegion.of(0f, 0f, 1f / cols, 1f / rows, pw, ph)
        val right = PageRegion.of(1f / cols, 0f, 2f / cols, 1f / rows, pw, ph)
        assertEquals("共边", left.right, right.left, eps)
    }

    @Test
    fun `单块页面的缩放不改变内容`() {
        // 只有一个分块时必须与整页渲染等价，否则缩放会改变画面
        val region = PageRegion.of(0f, 0f, 1f, 1f, 595, 842)
        val t = PageRegion.deviceTransform(region, 256, 256)
        assertEquals(256f / 595f, t[0], eps)
        assertEquals(256f / 842f, t[1], eps)
    }
}
