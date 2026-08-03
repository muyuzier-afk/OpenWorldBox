package com.openworldbox.game

/**
 * 目标选择策略
 *
 * 决定 KillAura 等模块"该打谁"。
 *
 * 选择流程：
 *   1. 过滤：根据 [TargetFilter] 排除不该攻击的实体
 *   2. 排序：根据 [TargetPriority] 选择优先目标
 *   3. 返回排序后的列表（首项即最佳目标）
 *
 * 设计为纯函数风格，不持有状态，每次 [select] 调用都基于最新数据。
 */
object TargetSelector {

    /**
     * 选择符合过滤条件的实体，按优先级排序。
     *
     * @param entities native 端拉取的全部实体
     * @param filter 过滤条件
     * @param originX/Y/Z 玩家坐标（用于距离/角度计算）
     * @param originYaw 玩家朝向 yaw（用于角度优先级）
     * @param originPitch 玩家朝向 pitch
     * @return 按优先级排序的目标列表（不含本地玩家）
     */
    fun select(
        entities: List<GameEntity>,
        filter: TargetFilter,
        originX: Double,
        originY: Double,
        originZ: Double,
        originYaw: Float,
        originPitch: Float
    ): List<GameEntity> {
        // 1. 过滤
        val candidates = entities.filter { entity ->
            // 排除自身
            if (entity.isLocalPlayer) return@filter false
            // 必须可被攻击
            if (!entity.isTargetable) return@filter false
            // 类型过滤
            if (!filter.matchesType(entity.type)) return@filter false
            // 距离过滤
            val dist = entity.distanceTo(originX, originY, originZ)
            if (dist > filter.maxRange) return@filter false
            // 穿墙过滤（如果禁止穿墙，由 native 端在 visibility 字段判断，
            // 这里简化为允许——后续可加 isWallBlocking 字段）
            // 名字过滤（黑名单）
            if (filter.nameBlacklist.any { entity.name.contains(it, ignoreCase = true) }) {
                return@filter false
            }
            true
        }

        // 2. 排序
        val sorted = when (filter.priority) {
            TargetPriority.DISTANCE -> candidates.sortedBy { it.distanceTo(originX, originY, originZ) }
            TargetPriority.ANGLE -> candidates.sortedBy {
                angleTo(originX, originY, originZ, originYaw, originPitch, it)
            }
            TargetPriority.HEALTH -> candidates.sortedBy { it.health }
            TargetPriority.ARMOR -> candidates.sortedBy { it.armor }
        }

        // 3. 角度上限过滤（在排序后再筛，避免漏选）
        return if (filter.maxAngleDeg < 180f) {
            sorted.filter {
                angleTo(originX, originY, originZ, originYaw, originPitch, it) <= filter.maxAngleDeg
            }
        } else {
            sorted
        }
    }

    /** 计算玩家朝向与目标实体之间的夹角（度） */
    private fun angleTo(
        ox: Double, oy: Double, oz: Double,
        oYaw: Float, oPitch: Float,
        target: GameEntity
    ): Float {
        // 视线方向向量（由 yaw/pitch 转换）
        val yawRad = Math.toRadians(oYaw.toDouble())
        val pitchRad = Math.toRadians(oPitch.toDouble())
        val dirX = -kotlin.math.cos(pitchRad) * kotlin.math.sin(yawRad)
        val dirY = -kotlin.math.sin(pitchRad)
        val dirZ = kotlin.math.cos(pitchRad) * kotlin.math.cos(yawRad)

        // 玩家到目标的方向
        val dx = target.x - ox
        val dy = target.y - oy
        val dz = target.z - oz
        val dlen = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dlen < 1e-6) return 0f
        val nx = dx / dlen
        val ny = dy / dlen
        val nz = dz / dlen

        // 点积 → 夹角
        val dot = (dirX * nx + dirY * ny + dirZ * nz).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(dot)).toFloat()
    }
}

/**
 * 目标过滤条件
 *
 * 由 KillAura 的 Bean 组合而成。
 */
data class TargetFilter(
    /** 优先级策略 */
    val priority: TargetPriority,
    /** 类型掩码（哪些类型的实体会被攻击） */
    val targetPlayers: Boolean,
    val targetHostile: Boolean,
    val targetPassive: Boolean,
    /** 最大攻击距离（方块数） */
    val maxRange: Double,
    /** 最大视角偏差（度），180 表示无限制 */
    val maxAngleDeg: Float,
    /** 是否允许穿墙 */
    val throughWalls: Boolean,
    /** 名字黑名单（不区分大小写，部分匹配） */
    val nameBlacklist: List<String> = emptyList()
) {
    fun matchesType(type: EntityType): Boolean = when (type) {
        EntityType.PLAYER -> targetPlayers
        EntityType.HOSTILE_MOB -> targetHostile
        EntityType.PASSIVE_MOB -> targetPassive
        EntityType.LOCAL_PLAYER -> false  // 自身永远不攻击
        EntityType.OTHER -> false
    }
}

/** 目标优先级策略 */
enum class TargetPriority(val displayName: String) {
    /** 离玩家最近 */
    DISTANCE("最近距离"),
    /** 与玩家视线夹角最小 */
    ANGLE("最小角度"),
    /** 血量最低（补刀） */
    HEALTH("最低血量"),
    /** 护甲最低（最易击杀） */
    ARMOR("最低护甲")
}
