package com.openworldbox.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.app.Activity
import android.view.KeyEvent
import com.openworldbox.ui.ImGuiBridge
import com.openworldbox.util.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Xposed 模块入口
 *
 * 参考自 WorldBox 工具箱的注入方案，针对网易我的世界中国版（com.netease.x19）：
 *
 *   1. 网易 MC 用 StubApp（com.netease.android.protect.StubApp）加固，
 *      handleLoadPackage 时游戏真实类尚未由壳子加载，
 *      Class.forName / findAndHookMethod(真实Activity,...) 全部失败。
 *   2. 必须 hook 网易加固壳的 StubApp.attachBaseContext(Context)，
 *      该方法在壳子初始化真实 dex 后被调用，此时能拿到真实 Activity/Context。
 *   3. 音量键 hook 也必须在壳子加载完真实 dex 后注册，
 *      否则 com.mojang.minecraftpe.MainActivity 类还不存在。
 *   4. Android 8+ 上，Xposed 注入后宿主进程内查询模块 APK 的 nativeLibraryDir
 *      可能为空，需要在 initZygote 阶段提前解压 so 并缓存路径。
 *
 * 作用域：com.netease.x19
 */
class HookInit : IXposedHookLoadPackage, IXposedHookZygoteInit {

    /** 模块 APK 路径（initZygote 缓存，注入时使用） */
    private var moduleApkPath: String = ""

    /** 模块 nativeLibraryDir（initZygote 解压 so 后缓存） */
    private var moduleNativeLibDir: String = ""

    /** 模块 ApplicationInfo（构造，供 GameActivity 用） */
    private var moduleAppInfo: ApplicationInfo? = null

    /** 防止重复注入 */
    @Volatile private var injected = false

    /** 防止音量键 hook 重复注册 */
    @Volatile private var inputHooked = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        Logger.i("========== OpenWorldBox initZygote ==========")
        Logger.i("modulePath=${startupParam.modulePath}")
        moduleApkPath = startupParam.modulePath

        try {
            // 构造模块 ApplicationInfo，供后续在宿主进程内定位模块资源
            val info = ApplicationInfo()
            info.sourceDir = moduleApkPath
            moduleAppInfo = info

            // Android 8+ 宿主进程内拿到的模块 nativeLibraryDir 可能为空，
            // 这里提前从 APK 解压 so 到模块目录，缓存路径供 GameActivity 加载。
            moduleNativeLibDir = extractNativeLibs(moduleApkPath)
            info.nativeLibraryDir = moduleNativeLibDir
            Logger.i("模块 nativeLibraryDir: $moduleNativeLibDir")
        } catch (t: Throwable) {
            Logger.e("initZygote 解压 native 库失败", t)
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 网易 MC 包名包含 x19
        if (!lpparam.packageName.contains("x19")) return

        Logger.i("========== OpenWorldBox HookInit loaded ==========")
        Logger.i("package=${lpparam.packageName}, process=${lpparam.processName}")

        // 只在主进程注入，避免 PushService 等子进程重复初始化
        if (lpparam.processName != lpparam.packageName) {
            Logger.i("非主进程，跳过: ${lpparam.processName}")
            return
        }

        // hook 网易加固壳 StubApp.attachBaseContext —— 真实入口
        hookStubApp(lpparam.classLoader)

        // 兼容：hook UniFixBase.a（网易 UniSDK 修复入口，部分版本在此触发）
        hookUniFix(lpparam.classLoader)

        // 兼容：fix Pangle（穿山甲广告 SDK）在网易壳下崩溃
        fixPangleCrash(lpparam.classLoader)
    }

    /**
     * hook 网易加固壳 StubApp.attachBaseContext(Context)。
     *
     * 这是网易 MC 的真实入口：StubApp 是 Application 子类，attachBaseContext
     * 在壳子加载真实 dex 之后、Application.onCreate 之前被调用，
     * 此处能拿到真实 Context，进而定位游戏 Activity。
     *
     * 回调里依次：注入核心 + 注册音量键 hook。
     */
    private fun hookStubApp(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.netease.android.protect.StubApp",
                classLoader,
                "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as? Context ?: return
                        Logger.i("StubApp.attachBaseContext 触发，开始注入")
                        try {
                            injectCore(ctx)
                        } catch (t: Throwable) {
                            Logger.e("injectCore 异常", t)
                        }
                        try {
                            hookInputDispatch(classLoader)
                        } catch (t: Throwable) {
                            Logger.e("hookInputDispatch 异常", t)
                        }
                    }
                }
            )
            Logger.i("已 hook StubApp.attachBaseContext")
        } catch (t: Throwable) {
            // StubApp 不存在（非加固版本），回退 hook Application.onCreate
            Logger.w("StubApp 不存在，回退 hook Application.attachBaseContext: ${t.message}")
            hookApplicationFallback(classLoader)
        }
    }

    /**
     * 兼容路径：hook UniFixBase.a(Context)。
     *
     * 部分网易版本在 UniSDK 修复流程里触发，此时真实 dex 已加载，
     * 是注册音量键 hook 的另一时机。injectCore 幂等，重复调用安全。
     */
    private fun hookUniFix(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("com.netease.ntunisdk.unifix.UniFixBase")
            XposedHelpers.findAndHookMethod(
                cls, "a",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as? Context ?: return
                        Logger.i("UniFixBase.a 触发")
                        try {
                            injectCore(ctx)
                        } catch (t: Throwable) {
                            Logger.e("UniFix injectCore 异常", t)
                        }
                        try {
                            hookInputDispatch(classLoader)
                        } catch (t: Throwable) {
                            Logger.e("UniFix hookInputDispatch 异常", t)
                        }
                    }
                }
            )
            Logger.i("已 hook UniFixBase.a")
        } catch (_: Throwable) {
            // 非网易 UniSDK 版本，忽略
        }
    }

    /**
     * 修复穿山甲 SDK FileProvider 在网易壳下的崩溃。
     * 非必需，但能避免部分版本启动崩溃影响注入。
     */
    private fun fixPangleCrash(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.bytedance.pangle.FileProvider",
                classLoader, "attachInfo",
                Context::class.java,
                android.content.pm.ProviderInfo::class.java,
                de.robv.android.xposed.XC_MethodReplacement.returnConstant(null)
            )
        } catch (_: Throwable) {
            // 无穿山甲 SDK，忽略
        }
    }

    /**
     * 注入核心：定位游戏 Activity，触发 GameActivity.start。
     *
     * StubApp.attachBaseContext 时游戏 Activity 尚未创建，
     * 但 Application 已就绪，可注册 Activity 生命周期监听等待 Activity 出现。
     * 此处复用现有 GameActivity.start（已实现 native 加载 + overlay 挂载）。
     *
     * 简化策略：通过 Application.registerActivityLifecycleCallbacks
     * 监听首个 Activity onCreate，命中即注入。
     */
    private fun injectCore(ctx: Context) {
        if (injected) return
        injected = true

        // 把预解压的 native 库目录注入 GameActivity，绕过宿主 PackageManager 查询
        if (moduleNativeLibDir.isNotEmpty()) {
            GameActivity.setModuleNativeLibDir(moduleNativeLibDir)
        }

        val app = ctx.applicationContext
        Logger.i("injectCore: 注册 Activity 生命周期监听，等待游戏 Activity 创建")

        val appClass = app.javaClass
        try {
            // 反射注册 ActivityLifecycleCallbacks（避免编译期依赖 AndroidX）
            val cbClass = Class.forName("android.app.Application\$ActivityLifecycleCallbacks")
            val registerMethod = appClass.getMethod("registerActivityLifecycleCallbacks", cbClass)

            // 动态代理实现回调
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                appClass.classLoader,
                arrayOf(cbClass)
            ) { _, method, args ->
                if (method.name == "onActivityCreated") {
                    val activity = args?.getOrNull(0) as? Activity
                    if (activity != null) {
                        Logger.i("Activity 创建: ${activity.javaClass.name}")
                        try {
                            GameActivity.start(activity)
                        } catch (t: Throwable) {
                            Logger.e("GameActivity.start 异常", t)
                        }
                    }
                }
                null
            }
            registerMethod.invoke(app, handler)
            Logger.i("已注册 Activity 生命周期监听")
        } catch (t: Throwable) {
            Logger.e("注册 ActivityLifecycleCallbacks 失败", t)
        }
    }

    /**
     * hook 游戏音量键 + 触摸事件。
     *
     * 必须在壳子加载真实 dex 后调用（StubApp.attachBaseContext / UniFixBase.a 回调里），
     * 此时 com.mojang.minecraftpe.MainActivity 类已存在。
     *
     * hook 三个方法：
     *   - dispatchKeyEvent: 拦截音量上键切换菜单
     *   - dispatchTouchEvent: 转发触摸到 ImGui
     *   - dispatchGenericMotionEvent: 转发手柄/摇杆（预留）
     */
    private fun hookInputDispatch(classLoader: ClassLoader) {
        if (inputHooked) return
        try {
            // 验证类已加载
            classLoader.loadClass("com.mojang.minecraftpe.MainActivity")
        } catch (_: Throwable) {
            Logger.w("com.mojang.minecraftpe.MainActivity 尚未加载，音量键 hook 延迟")
            return
        }
        inputHooked = true

        // 1. 音量键切换菜单
        try {
            XposedHelpers.findAndHookMethod(
                "com.mojang.minecraftpe.MainActivity",
                classLoader, "dispatchKeyEvent",
                KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val event = param.args[0] as? KeyEvent ?: return
                        if (event.keyCode != TOGGLE_KEYCODE) return
                        if (event.action != KeyEvent.ACTION_DOWN) return
                        Logger.i("捕获 VOLUME_UP，切换菜单")
                        ImGuiBridge.toggleMenu()
                        param.result = true
                    }
                }
            )
            Logger.i("已 hook MainActivity.dispatchKeyEvent (VOLUME_UP)")
        } catch (t: Throwable) {
            Logger.e("hook dispatchKeyEvent 失败", t)
        }
    }

    /**
     * 回退方案：hook Application.attachBaseContext。
     * 仅当 StubApp 不存在时使用（非加固版本）。
     */
    private fun hookApplicationFallback(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader, "attachBaseContext",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args[0] as? Context ?: return
                        Logger.i("Application.attachBaseContext 触发（回退方案）")
                        try {
                            injectCore(ctx)
                            hookInputDispatch(classLoader)
                        } catch (t: Throwable) {
                            Logger.e("回退注入异常", t)
                        }
                    }
                }
            )
            Logger.i("已 hook Application.attachBaseContext（回退）")
        } catch (t: Throwable) {
            Logger.e("回退 hook Application 失败", t)
        }
    }

    /**
     * 从模块 APK 解压 native 库到模块目录，返回解压目录。
     *
     * Android 8+ 上，Xposed 注入后宿主进程查询模块 APK 的 nativeLibraryDir
     * 可能为空（系统不再自动解压 lib 到 data/app）。这里手动从 APK 的
     * lib/<abi>/ 目录解压 so 文件到模块私有目录，供 System.load 使用。
     *
     * 解压路径：<moduleApkDir>/lib/<abi>/
     * 返回该路径作为 nativeLibraryDir。
     */
    private fun extractNativeLibs(apkPath: String): String {
        val apkFile = File(apkPath)
        val libDir = File(apkFile.parentFile, "owb_lib/arm64-v8a")
        if (!libDir.exists()) libDir.mkdirs()

        // 检查是否已解压（避免重复 IO）
        val soFile = File(libDir, "libopenworldbox.so")
        if (soFile.exists() && soFile.length() > 0) {
            Logger.i("native 库已解压: ${soFile.absolutePath}")
            return libDir.absolutePath
        }

        ZipFile(apkPath).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                // 只解压 arm64-v8a 的 so
                if (name.startsWith("lib/arm64-v8a/") && name.endsWith(".so")) {
                    val soName = name.substringAfterLast("/")
                    val out = File(libDir, soName)
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Logger.i("解压 so: $soName (${out.length()} bytes)")
                }
            }
        }
        return libDir.absolutePath
    }

    companion object {
        /** 网易我的世界中国版包名标识 */
        const val TARGET_PACKAGE_HINT = "x19"

        /** 切换菜单的快捷键 */
        const val TOGGLE_KEYCODE = KeyEvent.KEYCODE_VOLUME_UP
    }
}
