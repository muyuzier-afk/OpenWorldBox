package com.openworldbox.module

import com.openworldbox.config.Bean
import com.openworldbox.config.ConfigStore
import com.openworldbox.core.NativeBridge

/**
 * 模块基类
 *
 * 一个 Module 代表一项可独立开关的功能（如 ESP、KillAura、坐标显示等）。
 * 每个模块可以挂载若干 [Bean] 作为参数。
 *
 * 生命周期：
 *   register → loadState() → onEnabled()/onDisabled() → saveState()
 *
 * 模块开关状态会同步到 native 端（通过 [NativeBridge]），
 * 这样 native 渲染层可以决定是否绘制对应内容。
 *
 * 持久化：通过 [ConfigStore] 保存开关状态与 Bean 值，
 * 在 [ModuleManager.init] 时自动加载。
 *
 * 子类只需重写 [onEnabled] / [onDisabled]，并按需挂载 Bean。
 */
abstract class Module(
    val id: String,
    val displayName: String,
    val category: Category,
    val description: String = ""
) {
    /** 是否启用 */
    var enabled: Boolean = false
        private set

    /** 参数列表 */
    private val _options = mutableListOf<Bean<*>>()
    val options: List<Bean<*>> get() = _options

    /** 添加一个配置项（链式） */
    protected fun <T> option(bean: Bean<T>): Bean<T> {
        _options.add(bean)
        return bean
    }

    /** 开启模块 */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        try {
            if (value) onEnabled() else onDisabled()
        } catch (t: Throwable) {
            com.openworldbox.util.Logger.e("模块[$id] ${if (value) "onEnabled" else "onDisabled"} 异常", t)
        }
        // 通知 native
        try {
            NativeBridge.nativeOnModuleStateChanged(id, enabled)
        } catch (_: Throwable) {
            // native 可能尚未加载，忽略
        }
        // 持久化（ModuleManager 转发）
        ModuleManager.persist()
    }

    /** 便捷切换 */
    fun toggle() = setEnabled(!enabled)

    /**
     * 设置 Bean 值并持久化。
     * 由 Bean 的 listener 触发；也可手动调用。
     */
    fun <T> setBeanValue(bean: Bean<T>, value: T) {
        if (bean.value == value) return
        bean.value = value
        ModuleManager.persist()
    }

    /**
     * 从快照恢复状态。
     * 由 ModuleManager.init 调用。
     */
    internal fun loadFromSnapshot(snap: ConfigStore.ModuleSnapshot?) {
        if (snap == null) return
        // 恢复 Bean 值
        for (bean in _options) {
            val v = snap.options[bean.key] ?: continue
            try {
                bean.fromJson(v)
            } catch (_: Throwable) {
                // 类型不匹配等，跳过
            }
        }
        // 恢复开关（最后才开，确保 Bean 已就绪）
        if (snap.enabled && !enabled) {
            enabled = true
            try {
                onEnabled()
            } catch (t: Throwable) {
                com.openworldbox.util.Logger.e("模块[$id] 加载时 onEnabled 异常", t)
            }
            try {
                NativeBridge.nativeOnModuleStateChanged(id, true)
            } catch (_: Throwable) {}
        }
    }

    /** 导出当前状态为快照 */
    internal fun toSnapshot(): ConfigStore.ModuleSnapshot {
        val opts = mutableMapOf<String, Any>()
        for (bean in _options) {
            opts[bean.key] = bean.toJson()
        }
        return ConfigStore.ModuleSnapshot(enabled, opts)
    }

    /** 模块启用时回调（在主线程） */
    protected open fun onEnabled() {}

    /** 模块禁用时回调（在主线程） */
    protected open fun onDisabled() {}

    /**
     * 每帧 tick（由 GL 线程触发）。
     * 默认空实现，子类按需重写。
     *
     * 注意：在 GL 线程调用，不要做主线程耗时操作。
     */
    open fun onTick() {}

    /** 通用读取 Bean 值的工具 */
    protected fun <T> Bean<T>.get(): T = value
}
