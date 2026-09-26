package com.sigpher.nopdf.common;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.IntRange;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;

import com.aaron.base.util.StatusBarUtils;
import com.blankj.utilcode.util.ConvertUtils;
import com.blankj.utilcode.util.Utils;

/**
 * @author Aaron aaronzzxup@gmail.com
 */
public final class UiManager {

    private static final String TAG = "UiManager";

    /** showShort 的默认位置：贴近底部、抬高 250px。 */
    private static final int SHORT_GRAVITY = Gravity.BOTTOM;
    private static final int SHORT_Y_OFFSET = 250;

    public static void setNavigationBarColor(Activity activity, int color) {
        activity.getWindow().setNavigationBarColor(color);
    }

    public static void setStatusBar(Activity activity, Toolbar toolbar) {
        toolbar.setPadding(0, ConvertUtils.dp2px(25), 0, 0);
        int version = Build.VERSION.SDK_INT;
        if (version >= Build.VERSION_CODES.M) {
            StatusBarUtils.setTransparent(activity, true);
        } else {
            StatusBarUtils.setTranslucent(activity, 60);
        }
    }

    public static void setTransparentStatusBar(Activity activity) {
        StatusBarUtils.setTransparent(activity);
    }

    public static void setTranslucentStatusBar(Activity activity) {
        StatusBarUtils.setTranslucent(activity, 100);
    }

    public static void setTransparentNavigationBar(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setNavigationBarColor(Color.TRANSPARENT);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        }
    }

    public static void setTransparentStatusBar(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.setNavigationBarColor(Color.TRANSPARENT);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
    }

    public static void setTranslucentStatusBar(Activity activity, @IntRange(from = 0, to = 255) int alpha) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            activity.getWindow().setStatusBarColor(Color.argb(alpha, 0, 0, 0));
        } else {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }
    }

    public static void showShort(CharSequence text) {
        show(text, Toast.LENGTH_SHORT, SHORT_GRAVITY, SHORT_Y_OFFSET);
    }

    public static void showShort(@StringRes int res) {
        showShort(getString(res));
    }

    public static void showLong(CharSequence text) {
        show(text, Toast.LENGTH_LONG, SHORT_GRAVITY, SHORT_Y_OFFSET);
    }

    public static void showLong(@StringRes int res) {
        showLong(getString(res));
    }

    public static void showCenterShort(CharSequence text) {
        show(text, Toast.LENGTH_SHORT, Gravity.CENTER, 0);
    }

    public static void showCenterShort(@StringRes int res) {
        showCenterShort(getString(res));
    }

    /**
     * 统一走系统 {@link Toast}，**不要**改回 utilcode 的 ToastUtils。
     *
     * <p>ToastUtils 在 {@code NotificationManagerCompat.areNotificationsEnabled()} 为 false 时
     * 会退化到 {@code ToastUtils$ToastWithoutNotification}，后者用
     * {@code WindowManager.LayoutParams.type = 2005}(TYPE_TOAST) 自绘窗口。TYPE_TOAST 在
     * Android 11+ 已被系统禁止，addView 会失败，结果是**所有提示完全不可见**——而调用方
     * 无从感知，现象就是「功能按了没反应」。
     *
     * <p>原先的 {@code app_toast} 自定义卡片布局同理：自定义 view 交给系统 Toast 后，
     * 文本是 show 之后才 setText 的，可靠性不好。现在直接用系统 Toast 的文本样式。
     */
    private static void show(CharSequence text, int duration, int gravity, int yOffset) {
        try {
            Toast toast = Toast.makeText(Utils.getApp(), text, duration);
            toast.setGravity(gravity, 0, yOffset);
            toast.show();
        } catch (Throwable t) {
            // 提示失败绝不能影响主流程，但也不能静默——留下日志便于排查。
            Log.e(TAG, "show toast failed: " + text, t);
        }
    }

    private static String getString(@StringRes int res) {
        Context context = Utils.getApp();
        return context == null ? "" : context.getString(res);
    }

    private UiManager() {}
}
