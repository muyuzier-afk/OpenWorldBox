package com.openworldbox.game

/**
 * 游戏实体数据
 *
 * 从 native 层（通过 NativeBridge.queryEntities）拿到的实体信息。
 * 包含 KillAura / ESP / 等模块所需的全部字段。
 *
 * 设计为不可变 data class，每帧由 native 重新填充。
 *
 * 坐标系：与游戏世界坐标系一致（X 东西 / Y 高度 / Z 南北）。
 *
 * @property entityId 游戏内实体 ID，用于后续攻击调用
 * @property type 实体类型分类
 * @property name 显示名（玩家名 / 怪物名翻译）
 * @property x 世界 X 坐标
 * @property y 世界 Y 坐标（脚部）
 * @property z 世界 Z 坐标
 * @property yaw 朝向 yaw（度）
 * @property pitch 朝向 pitch（度）
 * @property health 当前血量
 * @property maxHealth 最大血量
 * @property armor 护甲值
 * @property isOnGround 是否在地面上
 * @property isLocalPlayer 是否是当前玩家自己
 * @property isAlive 是否存活
 */
data class GameEntity(
    val entityId: Long,
    val type: EntityType,
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val health: Float,
    val maxHealth: Float,
    val armor: Int,
    val isOnGround: Boolean,
    val isLocalPlayer: Boolean,
    val isAlive: Boolean
) {
    /** 是否可见（血量大于 0 且存活） */
    val isTargetable: Boolean get() = isAlive && health > 0f

    /** 与指定坐标的水平距离（忽略 Y） */
    fun horizontalDistanceTo(x: Double, z: Double): Double {
        val dx = x - this.x
        val dz = z - this.z
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    /** 与指定坐标的 3D 距离 */
    fun distanceTo(x: Double, y: Double, z: Double): Double {
        val dx = x - this.x
        val dy = y - this.y
        val dz = z - this.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * 实体类型分类
 *
 * 用于 [com.openworldbox.module.impl.KillAuraModule] 的目标过滤。
 */
enum class EntityType {
    /** 玩家（其他用户） */
    PLAYER,
    /** 敌对怪物（僵尸、骷髅等） */
    HOSTILE_MOB,
    /** 友好生物（牛、羊等） */
    PASSIVE_MOB,
    /** 自身 */
    LOCAL_PLAYER,
    /** 其他（掉落物、矿车等） */
    OTHER
}
