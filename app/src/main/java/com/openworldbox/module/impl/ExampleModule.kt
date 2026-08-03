package com.openworldbox.module.impl

import com.openworldbox.config.BoolBean
import com.openworldbox.config.FloatBean
import com.openworldbox.module.Category
import com.openworldbox.module.Module
import com.openworldbox.util.Logger

/**
 * 示例模块
 *
 * 用于验证模块注册链路、Bean 配置、native 状态同步是否正常。
 * 不实际 Hook 任何游戏行为，仅作为开发参考。
 *
 * 在菜单中应能看到：
 *   [其他] 示例模块
 *     - 开关
 *     - 浮点参数（默认 1.0）
 *     - 调试日志开关
 */
object ExampleModule : Module(
    id = "example",
    displayName = "示例模块",
    category = Category.MISC,
    description = "开发用示例，不实际修改游戏。可用于验证模块系统是否工作。"
) {
    private val intensity = option(FloatBean(
        key = "intensity",
        displayName = "强度",
        defaultValue = 1.0f,
        min = 0f,
        max = 10f
    ))

    private val verbose = option(BoolBean(
        key = "verbose",
        displayName = "调试日志",
        defaultValue = true
    ))

    override fun onEnabled() {
        Logger.i("ExampleModule 已启用，强度=${intensity.get()}，verbose=${verbose.get()}")
    }

    override fun onDisabled() {
        Logger.i("ExampleModule 已禁用")
    }
}
