package com.openworldbox.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View
import com.openworldbox.core.NativeBridge
import com.openworldbox.util.Logger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 渲染 Overlay
 *
 * 一个透明的 GLSurfaceView，挂在游戏窗口之上。
 * - GLES 3.0 上下文，与 ImGui 默认后端兼容
 * - 默认 FLAG_NOT_FOCUSABLE，触摸事件穿透到游戏
 * - 菜单显示时，setSystemUiVisibility/flags 切换为可交互
 *
 * 渲染回调直接转给 [NativeBridge]，由 native 端调用 ImGui 完成绘制。
 */
class RenderOverlay(context: Context) : GLSurfaceView(context) {

    private val renderer = OverlayRenderer()

    init {
        setEGLContextClientVersion(3)
        // 透明表面，8bit alpha
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // 默认不拦截触摸
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 把触摸事件转发给 native（用于 ImGui 输入）
        val action = event.actionMasked
        val x = event.x
        val y = event.y
        try {
            NativeBridge.nativeTouchEvent(action, x, y)
        } catch (_: Throwable) { /* native 未加载时忽略 */ }
        // 如果菜单可见，消费事件；否则让事件穿透
        return try { NativeBridge.nativeIsMenuVisible() } catch (_: Throwable) { false }
    }

    /**
     * 切换菜单显隐。
     * 菜单显示时需要把 overlay 设为可交互。
     */
    fun toggleMenu() {
        val visible = try { NativeBridge.nativeIsMenuVisible() } catch (_: Throwable) { false }
        val next = !visible
        NativeBridge.nativeSetMenuVisible(next)
        // 同步焦点状态
        isFocusable = next
        isFocusableInTouchMode = next
        isClickable = next
        if (next) requestFocus() else clearFocus()
        Logger.i("菜单 ${if (next) "打开" else "关闭"}")
    }

    /** 渲染器：桥接到 native */
    private inner class OverlayRenderer : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Logger.d("onSurfaceCreated")
            try {
                NativeBridge.nativeInit()
            } catch (t: Throwable) {
                Logger.e("nativeInit 失败", t)
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            Logger.d("onSurfaceChanged: ${width}x${height}")
            try {
                NativeBridge.nativeSurfaceChanged(width, height)
            } catch (_: Throwable) {}
        }

        override fun onDrawFrame(gl: GL10?) {
            try {
                NativeBridge.nativeRender()
            } catch (_: Throwable) {}
        }
    }
}
