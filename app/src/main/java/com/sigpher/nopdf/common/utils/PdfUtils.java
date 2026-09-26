package com.sigpher.nopdf.common.utils;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.blankj.utilcode.util.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * PDF 基础工具（页数、页面尺寸、渲染位图）。
 *
 * 注意：这里的 PdfRenderer / ParcelFileDescriptor 必须显式关闭。
 * PdfRenderer 持有 native 句柄与一个已打开的文件描述符，而导入流程会对每本书
 * 调用本类（渲染封面 + 取页数），泄漏会累积到「too many open files」。
 * 因此统一用 try-with-resources 包裹，并在 finally 里返回（return 会吞掉异常）。
 *
 * @author Aaron aaronzzxup@gmail.com
 */
public final class PdfUtils {

    private static final String TAG = "PdfUtils";

    public static Bitmap pdfToBitmap(String path, int curPage) {
        if (StringUtils.isEmpty(path)) {
            return null;
        }
        Bitmap bitmap = null;
        try (ParcelFileDescriptor pfd = openReadOnly(path);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (curPage < 0 || curPage >= renderer.getPageCount()) {
                return null;
            }
            try (PdfRenderer.Page page = renderer.openPage(curPage)) {
                int screenW = ScreenUtils.getScreenWidth();
                int screenH = ScreenUtils.getScreenHeight();
                int width = Math.min(screenW, screenH);
                float ratio = page.getWidth() / 1.0f / page.getHeight();
                if (ratio <= 0 || Float.isNaN(ratio) || Float.isInfinite(ratio)) {
                    return null;
                }
                int height = (int) (width / ratio);
                if (height <= 0) {
                    return null;
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                // 渲染前先清空，否则残留上一次的内容
                bitmap.eraseColor(0xFFFFFFFF);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
        } catch (IOException | SecurityException | IllegalArgumentException
                | OutOfMemoryError e) {
            // 加密、损坏、页码越界、无权限都退化为「取不到图」，不能影响导入流程
            LogUtils.d(TAG, "pdfToBitmap failed: " + path + ", " + e.getMessage());
            return null;
        }
        return bitmap;
    }

    public static String saveBitmap(Bitmap bitmap, String savePath) {
        if (bitmap == null) {
            return null;
        }
        File file = new File(savePath);
        if (file.exists()) {
            return savePath;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
        } catch (IOException e) {
            LogUtils.d(TAG, "saveBitmap failed: " + savePath + ", " + e.getMessage());
        }
        return savePath;
    }

    public static int getPdfTotalPage(String path) {
        if (StringUtils.isEmpty(path)) {
            return 0;
        }
        try (ParcelFileDescriptor pfd = openReadOnly(path);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            return renderer.getPageCount();
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            LogUtils.d(TAG, "getPdfTotalPage failed: " + path + ", " + e.getMessage());
            return 0;
        }
    }

    public static int getPdfWidth(String path, int page) {
        return getPdfSize(path, page, true);
    }

    public static int getPdfHeight(String path, int page) {
        return getPdfSize(path, page, false);
    }

    private static int getPdfSize(String path, int page, boolean width) {
        if (StringUtils.isEmpty(path)) {
            return 0;
        }
        try (ParcelFileDescriptor pfd = openReadOnly(path);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (page < 0 || page >= renderer.getPageCount()) {
                return 0;
            }
            try (PdfRenderer.Page p = renderer.openPage(page)) {
                return width ? Math.round(p.getWidth()) : Math.round(p.getHeight());
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            LogUtils.d(TAG, "getPdfSize failed: " + path + ", " + e.getMessage());
            return 0;
        }
    }

    /**
     * 只读方式打开 PDF。
     *
     * 之前用的是 MODE_READ_WRITE：既无必要（全程只读），又会在只读介质或
     * 分区存储下直接打不开文件。
     */
    private static ParcelFileDescriptor openReadOnly(String path) throws IOException {
        return ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private PdfUtils() {
    }
}
