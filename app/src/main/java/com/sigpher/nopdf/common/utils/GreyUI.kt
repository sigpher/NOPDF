package com.sigpher.nopdf.common.utils

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.Window

/**
 * @author aaronzzxup@gmail.com
 * @since 2020/7/4
 */
object GreyUI {

    /**
     * 按需给整个窗口加/去灰度图层。
     *
     * 注意 [View.LAYER_TYPE_HARDWARE] 即使不带 paint，也会让 decorView 先渲染进一块
     * 全屏离屏缓冲再合成——全屏阅读页等于每帧多一次全屏拷贝。原实现在 `value == false`
     * 时也照样设置图层，而灰度默认关闭，等于**每个界面都白白付这份开销**；
     * 关闭时必须显式回到 [View.LAYER_TYPE_NONE]。
     */
    fun grey(window: Window?, value: Boolean) {
        window ?: return

        val decorView = window.decorView
        if (value) {
            decorView.setLayerType(View.LAYER_TYPE_HARDWARE, makePaint())
        } else {
            decorView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }

    private fun makePaint(): Paint {
        return Paint().apply {
            val matrix = ColorMatrix().apply {
                setSaturation(0f)
            }
            colorFilter = ColorMatrixColorFilter(matrix)
        }
    }
}
