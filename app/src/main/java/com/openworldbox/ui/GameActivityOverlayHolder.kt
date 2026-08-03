package com.openworldbox.ui

/**
 * 全局持有当前挂载的 [RenderOverlay]。
 *
 * 游戏内 overlay 由 [com.openworldbox.core.GameActivity] 在主线程挂载到游戏窗口，
 * 但菜单切换、ImGui 绘制等逻辑运行在 GL 线程，且分布在多个类中
 * （[ImGuiBridge]、[RenderOverlay] 等）。
 *
 * 用一个轻量 object 持有 overlay 引用，避免各处相互强耦合。
 */
object GameActivityOverlayHolder {
    @Volatile
    var overlay: RenderOverlay? = null
}
