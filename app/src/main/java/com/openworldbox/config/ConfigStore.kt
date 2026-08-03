package com.openworldbox.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置持久化
 *
 * 用 SharedPreferences 存储一份 JSON 文档，包含所有模块的：
 *   - 启用状态
 *   - 各 Bean 的当前值
 *
 * 存储格式（key = PREF_NAME）：
 * {
 *   "modules": {
 *     "example": { "enabled": false, "options": { "intensity": 1.0, ... } },
 *     "killaura": { "enabled": true,  "options": { "range": 4.5, "cps": 8, ... } }
 *   }
 * }
 *
 * 由 [com.openworldbox.module.ModuleManager] 在初始化时加载、
 * 在模块开关或 Bean 值变化时保存。
 *
 * 设计为静态单例：游戏进程内只有一份配置。
 */
object ConfigStore {

    private const val PREF_NAME = "openworldbox_config"
    private const val KEY_ROOT = "config_json"

    private var prefs: SharedPreferences? = null

    /** 初始化，需在 ModuleManager.init 之前调用 */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 加载整个配置树。
     * 返回的 Map: moduleId -> (enabled, options: Map<beanKey, value>)
     */
    fun loadAll(): Map<String, ModuleSnapshot> {
        val sp = prefs ?: return emptyMap()
        val json = sp.getString(KEY_ROOT, null) ?: return emptyMap()
        return try {
            val root = JSONObject(json)
            val modules = root.optJSONObject("modules") ?: return emptyMap()
            val result = mutableMapOf<String, ModuleSnapshot>()
            val keys = modules.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val m = modules.getJSONObject(id)
                val enabled = m.optBoolean("enabled", false)
                val opts = m.optJSONObject("options")
                val optsMap = mutableMapOf<String, Any>()
                if (opts != null) {
                    val optKeys = opts.keys()
                    while (optKeys.hasNext()) {
                        val k = optKeys.next()
                        optsMap[k] = opts.get(k)
                    }
                }
                result[id] = ModuleSnapshot(enabled, optsMap)
            }
            result
        } catch (t: Throwable) {
            com.openworldbox.util.Logger.w("加载配置失败: ${t.message}")
            emptyMap()
        }
    }

    /**
     * 保存整个配置树。
     * @param data moduleId -> snapshot
     */
    fun saveAll(data: Map<String, ModuleSnapshot>) {
        val sp = prefs ?: return
        try {
            val root = JSONObject()
            val modules = JSONObject()
            for ((id, snap) in data) {
                val m = JSONObject()
                m.put("enabled", snap.enabled)
                val opts = JSONObject()
                for ((k, v) in snap.options) {
                    when (v) {
                        is Boolean -> opts.put(k, v)
                        is Number -> opts.put(k, v)
                        is String -> opts.put(k, v)
                        else -> opts.put(k, v.toString())
                    }
                }
                m.put("options", opts)
                modules.put(id, m)
            }
            root.put("modules", modules)
            sp.edit().putString(KEY_ROOT, root.toString()).apply()
        } catch (t: Throwable) {
            com.openworldbox.util.Logger.w("保存配置失败: ${t.message}")
        }
    }

    /** 单个模块的快照 */
    data class ModuleSnapshot(
        val enabled: Boolean,
        val options: Map<String, Any>
    )
}
