package com.openworldbox.core

import com.openworldbox.ui.ImGuiBridge
import com.openworldbox.util.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent

/**
 * Xposed 模块入口
 *
 * 由 assets/xposed_init 指定，LSPosed/EdXposed 在每次加载目标包时调用。
 *
 * 作用域：com.netease.x19（网易我的世界中国版）
 */
class HookInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        Logger.i("HookInit loaded in ${lpparam.packageName}, proc=${lpparam.processName}")

        // 优先 hook 网易封装的 Activity，回退到 Mojang 原版
        val activityName = resolveMainActivity(lpparam.classLoader) ?: run {
            Logger.w("未找到游戏 MainActivity，跳过注入")
            return
        }
        Logger.i("目标 MainActivity: $activityName")

        try {
            XposedHelpers.findAndHookMethod(
                activityName,
                lpparam.classLoader,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        Logger.i("游戏 onCreate 完成，启动注入流程")
                        GameActivity.start(activity)
                    }
                }
            )
        } catch (t: Throwable) {
            Logger.e("hook onCreate 失败", t)
        }

        // hook 按键事件：音量上键切换菜单显隐
        // 拦截在游戏之前，避免被游戏消费
        hookMenuToggleKey(activityName, lpparam.classLoader)
    }

    /**
     * 探测游戏主 Activity 类名。
     *
     * 网易 x19 的入口通常是 com.mojang.minecraftpe.MainActivity，
     * 部分版本会被网易壳子替换，这里尝试两种常见路径。
     */
    private fun resolveMainActivity(cl: ClassLoader): String? {
        val candidates = listOf(
            "com.mojang.minecraftpe.MainActivity",
            "com.netease.x19.MainActivity"
        )
        return candidates.firstOrNull { name ->
            try {
                Class.forName(name, false, cl)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    companion object {
        /** 网易我的世界中国版包名 */
        const val TARGET_PACKAGE = "com.netease.x19"

        /** 切换菜单的快捷键 */
        const val TOGGLE_KEYCODE = KeyEvent.KEYCODE_VOLUME_UP
    }

    /**
     * hook 游戏的 dispatchKeyEvent，拦截音量上键用于切换菜单。
     *
     * 使用 beforeHookedMethod + setResult(null) 阻止事件继续传给游戏。
     */
    private fun hookMenuToggleKey(activityName: String, classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                activityName,
                classLoader,
                "dispatchKeyEvent",
                KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? KeyEvent ?: return
                        if (event.keyCode != TOGGLE_KEYCODE) return
                        if (event.action != KeyEvent.ACTION_DOWN) return
                        Logger.i("捕获菜单切换键，切换菜单")
                        ImGuiBridge.toggleMenu()
                        // 阻止事件传给游戏
                        param.result = true
                    }
                }
            )
            Logger.i("已注册菜单切换键 hook (VOLUME_UP)")
        } catch (t: Throwable) {
            Logger.w("注册 dispatchKeyEvent hook 失败: ${t.message}")
        }
    }
}
