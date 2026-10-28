package com.fde.taskplugin.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;

/**
 * 屏幕 size / density / 字体缩放信息缓存。
 *
 * <p>系统“显示大小”（density）、“字体大小”（fontScale）或分辨率变化后，调用
 * {@link #refresh(Context)} 重新读取当前配置，再配合各监听器重建界面即可运行时适配。
 *
 * <p>FDE multi-display: taskbar instances are created per display, so this cache is keyed by
 * display id. Always pass the display/window context of the taskbar instance; do not use the
 * application context here, otherwise every instance would report the default display metrics.
 */
public class ScreenSizeUtils {
    private static final String TAG = "ScreenSizeUtils";
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;

    private static final SparseArray<ScreenSizeUtils> INSTANCES = new SparseArray<>();

    private int screenWidth = 0;
    private int screenHeight = 0;
    private float density = 1f;
    private float scaledDensity = 1f;
    private float fontScale = 1f;
    private int densityDpi = DisplayMetrics.DENSITY_DEFAULT;

    public static ScreenSizeUtils getInstance(Context mContext) {
        final int displayId = getDisplayId(mContext);
        ScreenSizeUtils instance = INSTANCES.get(displayId);
        if (instance == null) {
            synchronized (ScreenSizeUtils.class) {
                instance = INSTANCES.get(displayId);
                if (instance == null) {
                    instance = new ScreenSizeUtils(mContext);
                    INSTANCES.put(displayId, instance);
                }
            }
        }
        return instance;
    }

    private static int getDisplayId(Context context) {
        if (context == null) {
            return Display.DEFAULT_DISPLAY;
        }
        try {
            final Display display = context.getDisplay();
            if (display != null) {
                return display.getDisplayId();
            }
        } catch (Throwable t) {
            Log.w(TAG, "getDisplayId failed: " + t.getMessage());
        }
        return Display.DEFAULT_DISPLAY;
    }

    private ScreenSizeUtils(Context mContext) {
        update(mContext);
    }

    /** 系统 size/density/字体缩放变化后重新读取当前显示配置。 */
    public void refresh(Context context) {
        update(context);
    }

    private void update(Context context) {
        if (context == null) {
            return;
        }
        int realWidthPx = 0;
        int realHeightPx = 0;
        try {
            // Use the supplied (display/window) context, not getApplicationContext(): the latter
            // always resolves to the default display.
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            if (dm.density > 0) {
                density = dm.density;
            }
            if (dm.densityDpi > 0) {
                densityDpi = dm.densityDpi;
            }
            Configuration configuration = context.getResources().getConfiguration();
            fontScale = configuration.fontScale > 0 ? configuration.fontScale : 1f;
            scaledDensity = dm.scaledDensity > 0 ? dm.scaledDensity : density * fontScale;

            WindowManager manager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (manager != null) {
                DisplayMetrics realDm = new DisplayMetrics();
                manager.getDefaultDisplay().getRealMetrics(realDm);
                realWidthPx = realDm.widthPixels;
                realHeightPx = realDm.heightPixels;
            }
        } catch (Exception e) {
            Log.w(TAG, "update display metrics failed: " + e.getMessage());
        }

        // 以真实显示尺寸为准：getRealMetrics 能反映运行时的分辨率变化（含 wm size override、
        // FDE 分辨率切换）；openfde.display_* 属性只在拿不到真实尺寸时兜底。
        Integer propertyWidth = Utils.getProperty("openfde.display_width", 0);
        Integer propertyHeight = Utils.getProperty("openfde.display_height", 0);
        if (realWidthPx > 0 && realHeightPx > 0) {
            screenWidth = realWidthPx;
            screenHeight = realHeightPx;
        } else if (propertyWidth != null && propertyHeight != null
                && propertyWidth > 0 && propertyHeight > 0) {
            screenWidth = propertyWidth;
            screenHeight = propertyHeight;
        } else {
            screenWidth = DEFAULT_WIDTH;
            screenHeight = DEFAULT_HEIGHT;
        }
        Log.d(TAG, "update(display=" + getDisplayId(context) + "): real=" + realWidthPx + "x"
                + realHeightPx + " property=" + propertyWidth + "x" + propertyHeight
                + " -> " + screenWidth + "x" + screenHeight
                + " density=" + density + " densityDpi=" + densityDpi
                + " fontScale=" + fontScale);
    }

    /** 获取屏幕宽度（px） */
    public int getScreenWidth() {
        return screenWidth;
    }

    /** 获取屏幕高度（px） */
    public int getScreenHeight() {
        return screenHeight;
    }

    /** 获取当前逻辑密度（densityDpi / 160） */
    public float getDensity() {
        return density;
    }

    /** 获取字体缩放后的密度（density * fontScale） */
    public float getScaledDensity() {
        return scaledDensity;
    }

    /** 获取系统字体缩放系数（设置-显示-字体大小，0.85 ~ 2.0） */
    public float getFontScale() {
        return fontScale;
    }

    /** 获取当前 densityDpi（设置-显示-显示大小的实际值） */
    public int getDensityDpi() {
        return densityDpi;
    }

    /** dp -> px，使用当前 density 实时计算（不做缓存） */
    public int dp2px(float dp) {
        return (int) (dp * density + 0.5f);
    }
}
