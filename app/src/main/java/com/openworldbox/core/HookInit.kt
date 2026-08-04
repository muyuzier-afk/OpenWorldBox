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

        Logger.i("========== OpenWorldBox HookInit loaded ==========")
        Logger.i("package=${lpparam.packageName}, process=${lpparam.processName}")

        // 音量键 hook 不依赖游戏 Activity 类名，优先注册（hook 基类 Activity）。
        // 这样即使下面 Activity 探测失败，音量键仍能工作（配合 toggleMenu 的日志可定位故障）。
        hookMenuToggleKey()

        // 探测游戏主 Activity
        val activityName = resolveMainActivity(lpparam.classLoader)
        if (activityName == null) {
            Logger.e("未找到游戏 MainActivity（候选均不存在），onCreate 注入中止。" +
                     "若反复出现，请用 'adb shell dumpsys package com.netease.x19 | grep -A5 MAIN' " +
                     "查实际启动 Activity 类名反馈给开发者")
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
            "com.netease.x19.MainActivity",
            "com.netease.mc.MainActivity"
        )
        for (name in candidates) {
            try {
                Class.forName(name, false, cl)
                Logger.i("命中候选 Activity: $name")
                return name
            } catch (_: Throwable) {
                Logger.d("候选 Activity 不存在: $name")
            }
        }
        return null
    }

    companion object {
        /** 网易我的世界中国版包名 */
        const val TARGET_PACKAGE = "com.netease.x19"

        /** 切换菜单的快捷键 */
        const val TOGGLE_KEYCODE = KeyEvent.KEYCODE_VOLUME_UP
    }

    /**
     * hook 基类 Activity.dispatchKeyEvent 拦截音量上键，切换菜单显隐。
     *
     * 为什么 hook 基类而非游戏 Activity：网易 MC 用 NativeActivity/Mojang 引擎，
     * MainActivity 通常不重写 dispatchKeyEvent，用 findAndHookMethod(游戏Activity,...)
     * 会因找不到方法抛 NoSuchMethodError 导致 hook 静默失败。
     * hook 基类 Activity 后，子类未重写则调用会命中 hook。
     */
    private fun hookMenuToggleKey() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "dispatchKeyEvent",
                KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? KeyEvent ?: return
                        if (event.keyCode != TOGGLE_KEYCODE) return
                        if (event.action != KeyEvent.ACTION_DOWN) return
                        Logger.i("捕获 VOLUME_UP，切换菜单")
                        ImGuiBridge.toggleMenu()
                        // 阻止事件传给游戏
                        param.result = true
                    }
                }
            )
            Logger.i("已注册菜单切换键 hook (基类 Activity.dispatchKeyEvent, VOLUME_UP)")
        } catch (t: Throwable) {
            Logger.e("注册 dispatchKeyEvent hook 失败", t)
        }
    }
}
