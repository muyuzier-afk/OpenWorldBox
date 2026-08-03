package com.openworldbox.core

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.openworldbox.module.ModuleManager
import com.openworldbox.ui.GameActivityOverlayHolder
import com.openworldbox.ui.RenderOverlay
import com.openworldbox.util.Logger
import java.io.File

/**
 * 游戏内启动器
 *
 * 在游戏 Activity onCreate 后被调用，负责：
 * 1. 加载 native 库（libopenworldbox.so）
 * 2. 初始化模块系统
 * 3. 挂载 ImGui 渲染 overlay 到游戏窗口
 *
 * 不再像原版那样用 DexClassLoader 重载自身——Xposed 已经把我们的代码加载进游戏进程，
 * 只需解决 native 库路径问题（从模块 APK 的 nativeLibraryDir 加载）。
 */
object GameActivity {

    @Volatile private var initialized = false
    private var overlay: RenderOverlay? = null

    /** 模块 APK 路径（用于 native 库定位、APK 指纹等） */
    @Volatile var moduleApkPath: String = ""
        private set

    /** 主线程 Handler */
    val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 启动入口。在游戏 Activity onCreate 后调用。
     * 幂等：多次调用不会重复初始化。
     */
    @JvmStatic
    fun start(activity: Activity) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            // 1. 解析模块 APK 路径
            moduleApkPath = resolveModuleApkPath(activity)
            if (moduleApkPath.isEmpty()) {
                Logger.e("无法定位模块 APK 路径，注入中止")
                return
            }
            Logger.i("模块 APK 路径: $moduleApkPath")

            // 2. 加载 native 库
            try {
                loadNativeLibrary(activity)
            } catch (t: Throwable) {
                Logger.e("加载 native 库失败", t)
                return
            }

            // 3. 初始化模块系统
            try {
                ModuleManager.init(activity)
                ModuleManager.registerDefaults()
            } catch (t: Throwable) {
                Logger.e("模块系统初始化失败", t)
            }

            // 4. 挂载 overlay（必须在主线程）
            mainHandler.post {
                attachOverlay(activity)
            }

            initialized = true
            Logger.i("OpenWorldBox 注入完成")
        }
    }

    /**
     * 通过 PackageManager 查询自身（com.openworldbox）的 APK 安装路径。
     * 注意：在游戏进程中调用，需要跨包查询，权限通常足够（installed 包可见）。
     */
    private fun resolveModuleApkPath(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(MODULE_PACKAGE, PackageManager.GET_META_DATA)
            info.sourceDir
        } catch (t: Throwable) {
            Logger.w("查询模块 APK 失败: ${t.message}")
            ""
        }
    }

    /**
     * 加载 libopenworldbox.so。
     *
     * 优先用模块 APK 的 nativeLibraryDir 直接 load，
     * 失败则回退到 System.loadLibrary（依赖 ClassLoader，不一定有效）。
     */
    private fun loadNativeLibrary(context: Context) {
        val nativeDir = try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(MODULE_PACKAGE, PackageManager.GET_META_DATA)
            info.nativeLibraryDir
        } catch (_: Throwable) { "" }

        val soName = "libopenworldbox.so"
        if (nativeDir.isNotEmpty()) {
            val soFile = File(nativeDir, soName)
            if (soFile.exists()) {
                System.load(soFile.absolutePath)
                Logger.i("已加载 native 库: ${soFile.absolutePath}")
                return
            }
        }
        // 回退
        System.loadLibrary("openworldbox")
        Logger.i("已通过 loadLibrary 加载 native 库")
    }

    /**
     * 挂载渲染 overlay 到游戏窗口。
     *
     * 使用 WindowManager 添加一个 GLSurfaceView，覆盖在游戏画面之上。
     * 默认 FLAG_NOT_FOCUSABLE，菜单打开时切换为可交互。
     */
    private fun attachOverlay(activity: Activity) {
        if (overlay != null) return
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = RenderOverlay(activity).also { overlay = it }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // 应用内 overlay，不需要 SYSTEM_ALERT_WINDOW 权限
                WindowManager.LayoutParams.TYPE_APPLICATION,
                // 默认不拦截触摸，让事件穿透到游戏
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            wm.addView(view, params)
            GameActivityOverlayHolder.overlay = view
            Logger.i("Overlay 已挂载")
        } catch (t: Throwable) {
            Logger.e("挂载 overlay 失败", t)
        }
    }

    private const val MODULE_PACKAGE = "com.openworldbox"
}
