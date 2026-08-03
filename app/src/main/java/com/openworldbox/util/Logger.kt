package com.openworldbox.util

import android.util.Log

/**
 * 统一日志工具
 *
 * Xposed 模块的日志会同时输出到 logcat（tag: OpenWorldBox）
 * 和 Xposed 自带的日志面板（通过 XposedBridge.log）。
 */
object Logger {
    private const val TAG = "OpenWorldBox"

    private val xposedLog: ((String) -> Unit)? = try {
        // 反射调用 XposedBridge.log，避免编译期强依赖
        val cls = Class.forName("de.robv.android.xposed.XposedBridge")
        val method = cls.getMethod("log", String::class.java)
        ({ msg: String -> method.invoke(null, msg) })
    } catch (_: Throwable) {
        null
    }

    fun d(msg: String) {
        Log.d(TAG, msg)
        xposedLog?.invoke("[D] $msg")
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        xposedLog?.invoke("[I] $msg")
    }

    fun w(msg: String, t: Throwable? = null) {
        Log.w(TAG, msg, t)
        xposedLog?.invoke("[W] $msg ${t?.toString() ?: ""}")
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        xposedLog?.invoke("[E] $msg ${t?.toString() ?: ""}")
    }
}
