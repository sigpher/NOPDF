package com.github.barteksc.pdfviewer.util;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 临时诊断用的日志工具，**只服务于「滑动后整屏全白」这一个 bug 的定位**，定位完应当整块删掉。
 *
 * <p>为什么需要它：这是同一个症状的第七次尝试，之前的六次诊断全部是读代码推出来的，六次都错了。
 * 其中 0.5.4 那次之所以能定案，是因为改了主意去**量**（用 MuPDF 的 C 库写探针）。这一次症状已经
 * 收窄到「整屏全白、一页内容都没有」，而 {@code PDFView.onDraw} 走到整屏什么都不画只有三条路：
 *
 * <ol>
 *   <li>{@code recycled == true}（只有 {@code recycle()} 会置位，调用点是 {@code loadError} 与
 *       {@code onDetachedFromWindow}）</li>
 *   <li>{@code state != State.SHOWN}（{@code recycle()} 会把它打回 DEFAULT）</li>
 *   <li>缓存里确实没有可画的分块，且本轮一个任务都没排进去</li>
 * </ol>
 *
 * <p>这三条在源码里都能读出来「理论上不可能」，所以只能靠真机上的事实来分辨。日志就是用来把
 * 「哪一个」变成事实的：每个分支各打一条，用 {@code adb logcat -s NOPDFDIAG} 抓一份即可。
 *
 * <p>刻意做成集中的一条日志格式（而不是散落的十几种 tag）：一次复现抓一份日志就够，
 * 不用先猜该看哪个关键字。
 */
public final class Diag {

    /**
     * 总开关。诊断包发出去时置 true；修好之后把这个常量改成 false，或者把整块 {@code Diag} 调用
     * 删掉——它们对逻辑没有任何影响，只是 {@code Log} 调用。
     */
    public static boolean ENABLED = true;

    public static final String TAG = "NOPDFDIAG";

    private static final Map<String, Long> LAST = new HashMap<String, Long>();

    private Diag() {
    }

    public static void log(String message) {
        if (ENABLED) {
            Log.i(TAG, message);
        }
    }

    public static void log(String message, Throwable t) {
        if (ENABLED) {
            Log.i(TAG, message, t);
        }
    }

    /**
     * 带节流的日志：同名 {@code key} 在 {@code minIntervalMs} 内只打一次。
     *
     * <p>{@code loadPages()} 在每个 touch 事件和滑动动画的每一帧上都会跑，不节流的话一份日志能
     * 刷到几十 MB，真正有用的那几行会被淹掉。节流按 key 独立进行，所以「每帧都该记的量」和
     * 「罕见但必须记的异常」可以共存。
     */
    public static boolean due(String key, long minIntervalMs) {
        if (!ENABLED) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (LAST) {
            Long prev = LAST.get(key);
            if (prev != null && now - prev.longValue() < minIntervalMs) {
                return false;
            }
            LAST.put(key, Long.valueOf(now));
            return true;
        }
    }

    /** 浮点数只保留 1 位小数，并去掉 {@code -0.0}，免得日志长度被无意义的小数点撑爆。 */
    public static float f(float v) {
        return Math.round(v * 10f) / 10f;
    }
}
