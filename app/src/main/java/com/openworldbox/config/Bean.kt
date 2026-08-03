package com.openworldbox.config

/**
 * 配置项（Bean）基类
 *
 * 每个 Module 可以挂载若干个 Bean，用于提供可调节的参数。
 * 类型由子类决定：BoolBean / IntBean / FloatBean / StringBean / KeyBean / ColorBean / EnumBean
 *
 * 设计为简化版：直接用 Kotlin 属性 + Listener，不依赖 native 状态存储
 * （原版世界盒子工具的 Bean 全部走 native，这里改用纯 Kotlin 持久化）。
 */
abstract class Bean<T>(
    val key: String,
    val displayName: String,
    defaultValue: T,
    val description: String? = null
) {
    /** 当前值 */
    var value: T = defaultValue
        set(newValue) {
            if (field != newValue) {
                field = newValue
                listeners.forEach { it.onValueChanged(field) }
            }
        }

    /** 默认值，用于重置 */
    val defaultValue: T = defaultValue

    fun interface ValueListener<T> {
        fun onValueChanged(newValue: T)
    }

    private val listeners = mutableListOf<ValueListener<T>>()

    fun addListener(listener: ValueListener<T>) {
        listeners.add(listener)
    }

    /** 序列化为 JSON 友好的形式，供持久化使用 */
    abstract fun toJson(): Any

    /** 从 JSON 反序列化 */
    abstract fun fromJson(json: Any)
}

/** 布尔开关 */
class BoolBean(
    key: String,
    displayName: String,
    defaultValue: Boolean = false,
    description: String? = null
) : Bean<Boolean>(key, displayName, defaultValue, description) {
    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is Boolean) value = json
    }
}

/** 整数 */
class IntBean(
    key: String,
    displayName: String,
    defaultValue: Int = 0,
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE,
    description: String? = null
) : Bean<Int>(key, displayName, defaultValue, description) {
    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is Number) value = json.toInt().coerceIn(min, max)
    }
}

/** 浮点 */
class FloatBean(
    key: String,
    displayName: String,
    defaultValue: Float = 0f,
    val min: Float = Float.NEGATIVE_INFINITY,
    val max: Float = Float.POSITIVE_INFINITY,
    description: String? = null
) : Bean<Float>(key, displayName, defaultValue, description) {
    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is Number) value = json.toFloat().coerceIn(min, max)
    }
}

/** 字符串 */
class StringBean(
    key: String,
    displayName: String,
    defaultValue: String = "",
    description: String? = null
) : Bean<String>(key, displayName, defaultValue, description) {
    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is String) value = json
    }
}

/** 按键绑定（保存为键码字符串，如 "KEYCODE_F1"） */
class KeyBean(
    key: String,
    displayName: String,
    defaultValue: String = "",
    description: String? = null
) : Bean<String>(key, displayName, defaultValue, description) {
    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is String) value = json
    }
}

/** 颜色（ARGB int） */
class ColorBean(
    key: String,
    displayName: String,
    defaultValue: Int = 0xFFFFFFFF.toInt(),
    description: String? = null
) : Bean<Int>(key, displayName, defaultValue, description) {
    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is Number) value = json.toInt()
    }
}

/** 枚举（下拉选择） */
class EnumBean(
    key: String,
    displayName: String,
    val options: List<String>,
    defaultIndex: Int = 0,
    description: String? = null
) : Bean<Int>(key, displayName, defaultIndex, description) {
    val selectedOption: String get() = options.getOrElse(value) { options.first() }

    override fun toJson(): Any = value
    override fun fromJson(json: Any) {
        if (json is Number) value = json.toInt().coerceIn(0, options.size - 1)
    }
}
