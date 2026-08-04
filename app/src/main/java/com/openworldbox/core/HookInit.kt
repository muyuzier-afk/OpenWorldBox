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
 *
 * 关键设计：网易 MC 用动态 dex 加载，handleLoadPackage 时游戏 Activity 类
 * 尚未加载到 ClassLoader，Class.forName / findAndHookMethod(className,...) 都会失败。
 * 因此 hook 基类 Activity.onCreate(Bundle)，在回调里按类名前缀过滤，命中才注入。
 */
class HookInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        Logger.i("========== OpenWorldBox HookInit loaded ==========")
        Logger.i("package=${lpparam.packageName}, process=${lpparam.processName}")

        // 推送/后台进程不注入 overlay，省资源。只在主进程工作。
        if (lpparam.processName != TARGET_PACKAGE) {
            Logger.i("非主进程，跳过注入: ${lpparam.processName}")
            return
        }

        // 音量键 hook（基类 Activity.dispatchKeyEvent，不依赖游戏类加载）
        hookMenuToggleKey()

        // hook 基类 Activity.onCreate(Bundle)，回调里按类名前缀过滤
        hookGameActivityOnCreate()
    }

    /**
     * hook 基类 Activity.onCreate(Bundle)。
     *
     * 为什么不用 findAndHookMethod(具体类名, ...):
     *   网易 MC 用动态 dex 加载，handleLoadPackage 时游戏 Activity 类尚未加载，
     *   Class.forName / findAndHookMethod 都会失败。
     *
     * 方案：hook 基类 Activity.onCreate(Bundle)，所有 Activity 创建都会命中回调，
     *   在回调里按类名前缀过滤，只对游戏 Activity 注入。
     *   用 once-per-class 标记避免重复注入同一 Activity。
     */
    private fun hookGameActivityOnCreate() {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val className = activity.javaClass.name
                        if (!isGameActivity(className)) return
                        if (injectedClasses.contains(className)) return
                        injectedClasses.add(className)

                        Logger.i("命中游戏 Activity: $className (来自 ${param.method})")
                        Logger.i("游戏 onCreate 完成，启动注入流程")
                        try {
                            GameActivity.start(activity)
                        } catch (t: Throwable) {
                            Logger.e("注入流程异常", t)
                        }
                    }
                }
            )
            Logger.i("已 hook 基类 Activity.onCreate(Bundle)，等待游戏 Activity 创建")
        } catch (t: Throwable) {
            Logger.e("hook Activity.onCreate 失败", t)
        }
    }

    /**
     * 判断是否是游戏 Activity。
     *
     * 网易 MC 真实启动 Activity（来自 dumpsys）：
     *   - com.netease.minecraftpe.MainActivityDefault（默认入口）
     *   - com.netease.minecraftpe.MainActivityDynTestOxidized/Mid/Fresh（测试变体）
     *   - com.mojang.minecraftpe.MainActivity（xbox invite 入口）
     *
     * 用前缀匹配，兼容未来类名变体。同时打日志便于排查未命中情况。
     */
    private fun isGameActivity(className: String): Boolean {
        // 精确匹配已知启动入口
        val knownPrefixes = listOf(
            "com.netease.minecraftpe.MainActivity",
            "com.mojang.minecraftpe.MainActivity"
        )
        for (p in knownPrefixes) {
            if (className == p || className.startsWith("$p")) {
                return true
            }
        }
        return false
    }

    companion object {
        /** 网易我的世界中国版包名 */
        const val TARGET_PACKAGE = "com.netease.x19"

        /** 切换菜单的快捷键 */
        const val TOGGLE_KEYCODE = KeyEvent.KEYCODE_VOLUME_UP

        /** 已注入过的 Activity 类名集合，避免重复注入 */
        private val injectedClasses = mutableSetOf<String>()
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
