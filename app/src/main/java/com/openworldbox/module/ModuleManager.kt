package com.openworldbox.module

import android.content.Context
import com.openworldbox.config.ConfigStore
import com.openworldbox.module.impl.DumpModule
import com.openworldbox.module.impl.ExampleModule
import com.openworldbox.module.impl.KillAuraModule
import com.openworldbox.util.Logger

/**
 * 模块管理器
 *
 * 负责注册、查询、按分类聚合所有 [Module]。
 * 同时负责模块配置的持久化（通过 [ConfigStore]）。
 *
 * 模块本身是单例对象（object），在此处显式注册，
 * 后续新增模块只需在 [registerDefaults] 中加一行。
 *
 * 持久化流程：
 *   1. init() 时从 [ConfigStore] 加载所有模块的快照
 *   2. 注册每个模块后立即应用其快照（恢复开关与 Bean 值）
 *   3. 用户在菜单中切换开关 / 调整 Bean 时，由 Module 自动调用 [persist]
 */
object ModuleManager {

    private val _modules = mutableListOf<Module>()
    val modules: List<Module> get() = _modules.toList()

    @Volatile private var initialized = false

    @Volatile private var context: Context? = null

    /**
     * 初始化。在游戏注入早期调用一次。
     * 主要做：保存 context、初始化 ConfigStore、注册默认模块、加载持久化状态。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            this.context = context.applicationContext
            ConfigStore.init(context.applicationContext)
            initialized = true
            Logger.i("ModuleManager 初始化完成")
        }
    }

    /**
     * 注册默认模块集，并从持久化加载状态。
     * 新增模块时在此处添加即可。
     */
    fun registerDefaults() {
        // 先注册所有模块（此时 Bean 都是默认值、enabled=false）
        register(ExampleModule)
        register(KillAuraModule)
        register(DumpModule)

        // 再从持久化恢复
        loadPersistedState()
    }

    /** 注册单个模块 */
    fun register(module: Module) {
        if (_modules.any { it.id == module.id }) {
            Logger.w("模块 [${module.id}] 已注册，跳过")
            return
        }
        _modules.add(module)
        Logger.d("已注册模块: ${module.displayName} (${module.category.displayName})")
    }

    /** 从 ConfigStore 加载所有模块的状态 */
    private fun loadPersistedState() {
        val snapshots = ConfigStore.loadAll()
        if (snapshots.isEmpty()) {
            Logger.d("无持久化配置，使用默认值")
            return
        }
        for (m in _modules) {
            m.loadFromSnapshot(snapshots[m.id])
        }
        Logger.i("已加载持久化配置（${snapshots.size} 个模块）")
    }

    /** 把所有模块当前状态保存到 ConfigStore */
    fun persist() {
        val data = _modules.associate { it.id to it.toSnapshot() }
        ConfigStore.saveAll(data)
    }

    /** 按 ID 查找 */
    fun findById(id: String): Module? = _modules.firstOrNull { it.id == id }

    /** 按分类聚合 */
    fun getByCategory(category: Category): List<Module> =
        _modules.filter { it.category == category }

    /** 所有分类及其模块（按 [Category] 声明顺序） */
    fun grouped(): List<Pair<Category, List<Module>>> =
        Category.entries.map { cat -> cat to getByCategory(cat) }
            .filter { it.second.isNotEmpty() }

    /**
     * 每帧 tick：通知所有已启用模块。
     * 由 native 渲染循环通过 NativeBridge 调用。
     */
    fun tick() {
        for (m in _modules) {
            if (!m.enabled) continue
            try {
                m.onTick()
            } catch (t: Throwable) {
                Logger.e("模块[${m.id}] onTick 异常", t)
            }
        }
    }
}
