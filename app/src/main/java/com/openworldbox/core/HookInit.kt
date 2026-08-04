package com.openworldbox.core

import android.content.Context
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

        // 推送/后台进程不注入 overlay，省资源。只在主进程工作。
        if (lpparam.processName != TARGET_PACKAGE) {
            Logger.i("非主进程，跳过注入: ${lpparam.processName}")
            return
        }

        // 音量键 hook 不依赖游戏 Activity 类名，优先注册（hook 基类 Activity）。
        hookMenuToggleKey()

        // 动态探测游戏启动 Activity 类名
        val activityName = resolveMainActivity(lpparam)
        if (activityName == null) {
            Logger.e("未找到游戏启动 Activity，onCreate 注入中止。" +
                     "已尝试 PackageManager + 候选类名双路径，均失败。" +
                     "请在手机执行：adb shell cmd package resolve-activity --brief com.netease.x19 " +
                     "把输出反馈给开发者")
            return
        }
        Logger.i("目标启动 Activity: $activityName")

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
            Logger.i("已 hook $activityName.onCreate")
        } catch (t: Throwable) {
            Logger.e("hook onCreate 失败（类可能无 onCreate(Bundle) 签名）", t)
        }
    }

    /**
     * 探测游戏启动 Activity 类名。
     *
     * 策略一（推荐，版本无关）：用 PackageManager.getLaunchIntentForPackage
     *   拿系统注册的真实启动 Activity——无论网易壳子怎么改包名/类名都能命中。
     *   需要 Context，通过反射拿 ActivityThread.currentApplication()（Xposed 环境可用）。
     *
     * 策略二（回退）：硬编码候选类名，用 ClassLoader 探测。
     */
    private fun resolveMainActivity(lpparam: LoadPackageParam): String? {
        // === 策略一：PackageManager 动态查询 ===
        try {
            val ctx = currentContext()
            if (ctx != null) {
                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage(lpparam.packageName)
                if (intent != null && intent.component != null) {
                    val cls = intent.component!!.className
                    Logger.i("PackageManager 命中启动 Activity: $cls")
                    return cls
                }
                Logger.w("getLaunchIntentForPackage 返回 null，回退候选探测")
            } else {
                Logger.w("currentContext 为 null，回退候选探测")
            }
        } catch (t: Throwable) {
            Logger.w("PackageManager 查询失败: ${t.message}，回退候选探测")
        }

        // === 策略二：候选类名探测 ===
        val candidates = listOf(
            "com.mojang.minecraftpe.MainActivity",
            "com.netease.x19.MainActivity",
            "com.netease.mc.MainActivity",
            "com.netease.mc.WelcomeActivity",
            "com.netease.x19.WelcomeActivity"
        )
        for (name in candidates) {
            try {
                Class.forName(name, false, lpparam.classLoader)
                Logger.i("命中候选 Activity: $name")
                return name
            } catch (_: Throwable) {
                Logger.d("候选 Activity 不存在: $name")
            }
        }
        return null
    }

    /**
     * 获取当前应用 Context。
     * Xposed 注入目标进程后，通过 ActivityThread.currentApplication() 拿到宿主 Context。
     */
    private fun currentContext(): Context? {
        return try {
            val cl = Class.forName("android.app.ActivityThread")
            val method = cl.getDeclaredMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (t: Throwable) {
            Logger.w("获取 currentApplication 失败: ${t.message}")
            null
        }
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

