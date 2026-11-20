package com.fde.taskplugin.utils

import android.content.Context
import android.content.Intent
import android.text.TextUtils

/**
 * Settings 页面跳转辅助（两栏内嵌形态）。
 *
 * 对照 packages_apps_settings 里的实现：
 *
 * 1. `SettingsActivity.shouldShowMultiPaneDeepLink()` 只在 Intent 带 action 时才走 deep link /
 *    两栏内嵌流程。`Intent()` + `component` 这种空 action 写法会被当成普通子页面，全屏独立打开。
 *
 * 2. 内嵌时显示哪一页，是由 **解析出来的目标 Activity** 决定的：Settings 读该 Activity 在
 *    AndroidManifest 里声明的 `com.android.settings.FRAGMENT_CLASS` meta-data，再经
 *    `ACTION_SETTINGS_EMBED_DEEP_LINK_ACTIVITY` 把它塞进两栏首页的右边。
 *    也就是说 —— 不能把 component 换成 Settings 首页再塞 `:settings:show_fragment`：
 *    `SettingsHomepageActivity` 根本不读这个 extra（它在 onCreate 里只处理 two-pane 和首页列表），
 *    结果就是「打开了 Settings，但停在首页，没跳到目标页」。
 *
 * 所以这里只做最小改写：**保留调用方给的目标**（action / component / data / extras），
 * 只补一个非空 action 让内嵌流程能触发，并保证带上 FLAG_ACTIVITY_NEW_TASK。
 *
 * 用法上尽量传 Settings 自己声明的 action，例如：
 * - `android.settings.BLUETOOTH_PAIRING_SETTINGS`  → 蓝牙配对页
 * - `android.settings.TEXT_READING_SETTINGS`       → 文本朗读/字体大小页
 * - `android.settings.PRIVACY_SETTINGS`            → 隐私页（FDE 改成了应用兼容设置）
 * - `android.settings.WIRELESS_SETTINGS`           → 网络与互联网
 * - `Settings.ACTION_MANAGE_WRITE_SETTINGS`        → 修改系统设置权限
 */
object SettingsLauncher {

    /**
     * 纯 ComponentName 跳转的兜底 action。
     * Settings 只判断 action 是否为空，实际打开哪一页仍由 component 解析，不会被这个值影响。
     */
    private const val FALLBACK_ACTION = "com.fde.taskplugin.action.OPEN_SETTINGS"

    /** 打开 Settings 页面（内嵌形态）。 */
    fun open(context: Context, source: Intent) {
        context.startActivity(prepare(source))
    }

    /** 返回补齐了 action / FLAG_ACTIVITY_NEW_TASK 的 Intent。 */
    fun prepare(source: Intent): Intent {
        val intent = Intent(source)
        if (TextUtils.isEmpty(intent.action)) {
            intent.action = FALLBACK_ACTION
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}
