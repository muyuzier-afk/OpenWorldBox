package com.openworldbox

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 模块自身的占位 Activity
 *
 * Xposed 模块通常需要一个 Activity 用于在桌面显示图标（用户点击后可跳转到 LSPosed 设置）。
 * 实际功能不在此处启动——所有逻辑在 [com.openworldbox.core.HookInit] 注入到游戏后执行。
 *
 * 这里仅显示一个简单的说明页面。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "OpenWorldBox\n\n" +
                "这是一个 Xposed 模块。\n" +
                "请在 LSPosed 中启用本模块，并勾选作用域：网易我的世界 (com.netease.x19)。\n\n" +
                "启用后启动游戏即可。"
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(tv)
    }
}
