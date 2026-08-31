package com.fde.taskplugin

import android.content.Context

object GlobalSystemUIContext {
    @Volatile
    private lateinit var globalSystemUIContext: Context

    fun setContext(context: Context) {
        // 可选：只允许设置一次，避免覆盖
        if (!::globalSystemUIContext.isInitialized) {
            globalSystemUIContext = context.applicationContext
        }
    }

    fun getContext(): Context {
        // 如果未初始化就调用，会抛出异常（符合非空语义）
        return globalSystemUIContext
    }
}