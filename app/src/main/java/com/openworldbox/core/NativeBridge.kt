package com.openworldbox.core

/**
 * Native 桥接
 *
 * 所有 [externalNativeBuild] 生成的 JNI 函数在此声明为 Kotlin external。
 * 对应的 native 实现在 app/src/main/cpp/jni_bridge.cpp。
 *
 * 设计：native 层负责 ImGui 渲染与 GLES 调用；
 *       Kotlin 层负责菜单逻辑、模块状态、输入分发。
 *
 * 模块列表的传递方式：
 *   - native 每帧 render 时调用 Kotlin 端的 [kotlinRenderMenu] 回调
 *   - Kotlin 通过 JNI Env 调用 ImGuiBridge.renderMenu()
 *   - 在 Kotlin 中遍历 ModuleManager，调用 native 的即时绘制函数
 *     （nativeBeginRow/nativeCheckbox/nativeSlider 等）
 *
 * 这种"反向回调"模式让菜单结构由 Kotlin 控制，避免在 native 端维护一份镜像。
 */
object NativeBridge {

    // ============ 生命周期 ============

    /** 初始化 ImGui + GLES 后端。在 GLContext ready 后调用一次。 */
    @JvmStatic external fun nativeInit()

    /** 渲染一帧。由 GLSurfaceView.Renderer.onDrawFrame 调用。 */
    @JvmStatic external fun nativeRender()

    /** Surface 尺寸变化。 */
    @JvmStatic external fun nativeSurfaceChanged(width: Int, height: Int)

    /** 释放资源。 */
    @JvmStatic external fun nativeShutdown()

    // ============ 输入 ============

    /** 触摸事件转发。action 取值参考 MotionEvent.ACTION_*。 */
    @JvmStatic external fun nativeTouchEvent(action: Int, x: Float, y: Float)

    /** 按键事件转发。keyCode 参考 KeyEvent.KEYCODE_*。 */
    @JvmStatic external fun nativeKeyEvent(action: Int, keyCode: Int, unicodeChar: Int)

    // ============ 菜单状态 ============

    /** 切换菜单显示/隐藏。 */
    @JvmStatic external fun nativeSetMenuVisible(visible: Boolean)

    /** 查询菜单当前是否可见。 */
    @JvmStatic external fun nativeIsMenuVisible(): Boolean

    // ============ 模块数据 ============

    /**
     * 通知 native 端模块开关变化。
     * native 端可据此决定是否启用对应的 Hook/渲染。
     */
    @JvmStatic external fun nativeOnModuleStateChanged(moduleId: String, enabled: Boolean)

    // ============ 即时绘制（供 Kotlin 回调中使用） ============
    // 这些函数设计为只在 nativeRender 的菜单绘制阶段调用，
    // 由 Kotlin 端通过 ImGuiBridge.renderMenu() 反向调用。
    // 它们直接调用 ImGui 的对应 API，返回值反映了用户交互结果。

    /**
     * 绘制一个开关。返回 true 表示本次开关状态发生变化。
     * @param id 唯一 ID（用于 ImGui 内部状态）
     * @param label 显示文本
     * @param current 当前开关状态
     * @return 新状态（如果用户点击则与 current 不同）
     */
    @JvmStatic external fun nativeCheckbox(id: String, label: String, current: Boolean): Boolean

    /**
     * 绘制一个浮点滑块。
     * @return 用户拖动后的新值（未拖动则等于 current）
     */
    @JvmStatic external fun nativeSliderFloat(
        id: String, label: String, current: Float, min: Float, max: Float
    ): Float

    /**
     * 绘制一个整数滑块。
     */
    @JvmStatic external fun nativeSliderInt(
        id: String, label: String, current: Int, min: Int, max: Int
    ): Int

    /** 绘制一段文本 */
    @JvmStatic external fun nativeText(text: String)

    /** 折叠组开始。返回 true 表示展开中，需要继续绘制内部内容。 */
    @JvmStatic external fun nativeCollapsingHeader(label: String): Boolean

    /** TabBar 开始。返回当前选中 tab 索引（首次调用传入 -1）。 */
    @JvmStatic external fun nativeBeginTabBar(id: String): Int

    /** TabBar 结束 */
    @JvmStatic external fun nativeEndTabBar()

    /** 单个 Tab 项。返回 true 表示当前 Tab 被选中，需绘制其内容。 */
    @JvmStatic external fun nativeBeginTabItem(label: String): Boolean

    /** Tab 项结束 */
    @JvmStatic external fun nativeEndTabItem()

    /** 分隔线 */
    @JvmStatic external fun nativeSeparator()

    /** 同一行的开始 */
    @JvmStatic external fun nativeSameLine()

    /** 按钮。返回 true 表示本次被点击。 */
    @JvmStatic external fun nativeButton(label: String): Boolean

    // ============ 主窗口 ============

    /**
     * 开始主窗口。
     * @return true 表示窗口打开中，需要继续绘制内容；false 表示窗口被关闭。
     */
    @JvmStatic external fun nativeBeginMainWindow(
        title: String, x: Float, y: Float, w: Float, h: Float
    ): Boolean

    /** 结束主窗口（必须与 [nativeBeginMainWindow] 配对） */
    @JvmStatic external fun nativeEndMainWindow()

    // ============ 游戏数据 API（用于战斗/视觉模块） ============
    //
    // 这些方法目前是 stub（仅日志/返回空），等接入 native hook 后才能真实工作。
    // Kotlin 层先按真实接口编码，后续只需补 native 实现。

    /**
     * 获取本地玩家位置。
     * @return FloatArray 长度 3，{x, y, z}；失败返回 {0,0,0}
     */
    @JvmStatic external fun nativeGetLocalPlayerPos(): FloatArray

    /**
     * 获取本地玩家朝向。
     * @return FloatArray 长度 2，{yaw, pitch}（度）
     */
    @JvmStatic external fun nativeGetLocalPlayerRotation(): FloatArray

    /**
     * 查询视野内的实体（含玩家、怪物、动物）。
     *
     * 实体数据由 native 端打包为 FloatArray 返回：
     *   每个实体 14 个 float：
     *   [id(低32位), id(高32位), type, x, y, z, yaw, pitch, health, maxHealth, armor,
     *    isOnGround(0/1), isLocalPlayer(0/1), isAlive(0/1)]
     *
     * @return 实体数据扁平数组；空数组表示无实体或 stub 模式
     */
    @JvmStatic external fun nativeQueryEntities(): FloatArray

    /**
     * 对指定实体发起攻击。
     *
     * @param entityId 实体 ID
     * @param swingHand 是否同时挥手动画
     * @return true 表示攻击已发出
     */
    @JvmStatic external fun nativeAttackEntity(entityId: Long, swingHand: Boolean): Boolean

    /**
     * 设置玩家朝向（用于"silent rotation"——攻击时不实际转动玩家视角，
     * 但向服务器发包表示朝向某点）。
     *
     * 注意：调用后视角会保持，需要调用方自行恢复。
     *
     * @param yaw 度
     * @param pitch 度
     */
    @JvmStatic external fun nativeSetRotationSilent(yaw: Float, pitch: Float)

    /**
     * 检查玩家与目标之间是否有方块遮挡（射线检测）。
     * @return true 表示无遮挡（可攻击）；false 表示有墙阻挡
     */
    @JvmStatic external fun nativeIsLineOfSightClear(x: Double, y: Double, z: Double): Boolean

    /**
     * 触发模块 tick。
     * 由 native 渲染循环每帧调用，转发到 ModuleManager.tick()。
     */
    @JvmStatic external fun nativeTickModules()

    // ============ 调试 Dump（DEBUG 分类模块用） ============
    //
    // 这些方法在游戏进程内运行时执行，
    // 用于辅助逆向分析 libminecraftpe.so（被符号加密加固）。
    // 输出走 logcat（tag: OpenWorldBox-Dump），不在 UI 显示。

    /** 枚举所有已加载 .so，重点标记 libminecraftpe.so */
    @JvmStatic external fun nativeDumpLoadedModules(): Int

    /** 用 dlsym 解析指定符号，返回地址字符串（hex），失败返回空串 */
    @JvmStatic external fun nativeDumpResolveSymbol(symbol: String): String

    /**
     * Dump 指定模块指定偏移处的内存（hex）。
     * @param moduleName 模块名（如 "libminecraftpe.so"）
     * @param offset 模块内偏移
     * @param size 字节数
     */
    @JvmStatic external fun nativeDumpModuleMemory(moduleName: String, offset: Long, size: Int)

    /**
     * 在指定模块内存范围内扫描包含关键词的 ASCII 字符串。
     * @return 匹配数量
     */
    @JvmStatic external fun nativeDumpStringSearch(moduleName: String, keyword: String, maxResults: Int): Int

    /** 一次性执行完整扫描（模块列表 + 候选符号 + 关键字符串） */
    @JvmStatic external fun nativeDumpRunFullScan()
}
