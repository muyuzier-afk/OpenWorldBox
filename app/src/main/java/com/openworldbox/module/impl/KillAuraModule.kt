package com.openworldbox.module.impl

import com.openworldbox.config.BoolBean
import com.openworldbox.config.EnumBean
import com.openworldbox.config.FloatBean
import com.openworldbox.config.IntBean
import com.openworldbox.config.StringBean
import com.openworldbox.core.NativeBridge
import com.openworldbox.game.EntityType
import com.openworldbox.game.GameEntity
import com.openworldbox.game.TargetFilter
import com.openworldbox.game.TargetPriority
import com.openworldbox.game.TargetSelector
import com.openworldbox.module.Category
import com.openworldbox.module.Module
import com.openworldbox.util.Logger

/**
 * KillAura 自动攻击模块
 *
 * 参考原版"世界盒子工具"的字段命名：
 *   - rotationMode (Silent / Lock / Off)
 *   - range / CPS / 攻击冷却
 *   - 玩家/怪物/动物 多选
 *   - 穿墙攻击
 *
 * 工作流程（每帧 onTick）：
 *   1. 拉取本地玩家位置和朝向（native）
 *   2. 查询视野内实体（native）
 *   3. 用 [TargetSelector] 过滤+排序
 *   4. 取首位目标，根据 CPS 检查冷却
 *   5. 视角模式决定是否需要 setRotationSilent
 *   6. 调用 nativeAttackEntity 攻击
 *
 * 当前 native 层均为 stub，所以模块实际不会攻击任何实体，
 * 但所有逻辑、参数、状态机都已就绪——
 * 只需在 jni_bridge.cpp 中接入真实 hook 即可激活。
 *
 * 菜单中应看到（战斗 Tab）：
 *   KillAura
 *     选项:killaura
 *       - 攻击距离 (滑块 2~8，默认 4.5)
 *       - CPS (滑块 1~20，默认 8)
 *       - 视角模式 (枚举: 关闭/静默/锁定)
 *       - 攻击玩家 (开关，默认开)
 *       - 攻击怪物 (开关，默认开)
 *       - 攻击动物 (开关，默认关)
 *       - 穿墙攻击 (开关，默认关)
 *       - 优先级 (枚举: 距离/角度/血量/护甲)
 *       - 视角上限 (滑块 0~180，默认 90)
 *       - 名字黑名单 (文本，逗号分隔)
 */
object KillAuraModule : Module(
    id = "killaura",
    displayName = "KillAura",
    category = Category.COMBAT,
    description = "自动攻击范围内的目标。支持目标类型、优先级、视角模式。"
) {
    // ============ 参数 ============

    private val range = option(FloatBean(
        key = "range",
        displayName = "攻击距离",
        defaultValue = 4.5f,
        min = 2.0f,
        max = 8.0f
    ))

    private val cps = option(IntBean(
        key = "cps",
        displayName = "CPS (每秒攻击次数)",
        defaultValue = 8,
        min = 1,
        max = 20
    ))

    /** 视角模式 */
    private val rotationMode = option(EnumBean(
        key = "rotation_mode",
        displayName = "视角模式",
        options = listOf("关闭", "静默", "锁定"),
        defaultIndex = 1
    ))

    private val attackPlayers = option(BoolBean(
        key = "attack_players",
        displayName = "攻击玩家",
        defaultValue = true
    ))

    private val attackHostile = option(BoolBean(
        key = "attack_hostile",
        displayName = "攻击怪物",
        defaultValue = true
    ))

    private val attackPassive = option(BoolBean(
        key = "attack_passive",
        displayName = "攻击动物",
        defaultValue = false
    ))

    private val throughWalls = option(BoolBean(
        key = "through_walls",
        displayName = "穿墙攻击",
        defaultValue = false
    ))

    private val priority = option(EnumBean(
        key = "priority",
        displayName = "目标优先级",
        options = listOf(
            TargetPriority.DISTANCE.displayName,
            TargetPriority.ANGLE.displayName,
            TargetPriority.HEALTH.displayName,
            TargetPriority.ARMOR.displayName
        ),
        defaultIndex = 0
    ))

    private val maxAngle = option(FloatBean(
        key = "max_angle",
        displayName = "视角上限 (度)",
        defaultValue = 90f,
        min = 0f,
        max = 180f
    ))

    private val nameBlacklist = option(StringBean(
        key = "name_blacklist",
        displayName = "名字黑名单 (逗号分隔)",
        defaultValue = ""
    ))

    // ============ 状态 ============

    /** 上次攻击时间（毫秒） */
    private var lastAttackTimeMs: Long = 0L

    /** 当前目标（用于显示/调试） */
    @Volatile
    var currentTarget: GameEntity? = null
        private set

    // ============ 生命周期 ============

    override fun onEnabled() {
        Logger.i("KillAura 已启用: range=${range.get()}, cps=${cps.get()}, mode=${rotationMode.selectedOption}")
        lastAttackTimeMs = 0L
        currentTarget = null
    }

    override fun onDisabled() {
        Logger.i("KillAura 已禁用")
        currentTarget = null
    }

    // ============ 主逻辑 ============

    override fun onTick() {
        // 1. 拉取本地玩家信息
        val posArr = try { NativeBridge.nativeGetLocalPlayerPos() } catch (_: Throwable) { null }
        val rotArr = try { NativeBridge.nativeGetLocalPlayerRotation() } catch (_: Throwable) { null }
        if (posArr == null || posArr.size < 3) {
            currentTarget = null
            return
        }
        val px = posArr[0].toDouble()
        val py = posArr[1].toDouble()
        val pz = posArr[2].toDouble()
        val yaw = rotArr?.getOrNull(0) ?: 0f
        val pitch = rotArr?.getOrNull(1) ?: 0f

        // 2. 查询实体
        val entities = try {
            parseEntities(NativeBridge.nativeQueryEntities())
        } catch (t: Throwable) {
            Logger.w("解析实体失败: ${t.message}")
            emptyList()
        }
        if (entities.isEmpty()) {
            currentTarget = null
            return
        }

        // 3. 构建过滤器
        val filter = TargetFilter(
            priority = TargetPriority.entries[priority.get()],
            targetPlayers = attackPlayers.get(),
            targetHostile = attackHostile.get(),
            targetPassive = attackPassive.get(),
            maxRange = range.get().toDouble(),
            maxAngleDeg = maxAngle.get(),
            throughWalls = throughWalls.get(),
            nameBlacklist = parseBlacklist(nameBlacklist.get())
        )

        // 4. 选择目标
        val selected = TargetSelector.select(
            entities = entities,
            filter = filter,
            originX = px,
            originY = py,
            originZ = pz,
            originYaw = yaw,
            originPitch = pitch
        )

        if (selected.isEmpty()) {
            currentTarget = null
            return
        }

        val target = selected.first()
        currentTarget = target

        // 5. 视角处理
        val mode = rotationMode.get()
        if (mode == 1 /* 静默 */ || mode == 2 /* 锁定 */) {
            val (tyaw, tpitch) = computeAimAngle(px, py, pz, target)
            try {
                NativeBridge.nativeSetRotationSilent(tyaw, tpitch)
            } catch (_: Throwable) {}
            // 锁定模式还需要实际转动玩家视角（区别于静默）
            // 此处简化为只走 silent 通道
        }

        // 6. CPS 冷却检查
        val now = System.currentTimeMillis()
        val intervalMs = 1000L / cps.get().coerceAtLeast(1)
        if (now - lastAttackTimeMs < intervalMs) {
            return  // 还在冷却中
        }

        // 7. 穿墙检查（如禁用穿墙）
        if (!throughWalls.get()) {
            try {
                if (!NativeBridge.nativeIsLineOfSightClear(target.x, target.y, target.z)) {
                    return  // 有墙阻挡，跳过
                }
            } catch (_: Throwable) {}
        }

        // 8. 执行攻击
        val attacked = try {
            NativeBridge.nativeAttackEntity(target.entityId, swingHand = true)
        } catch (_: Throwable) { false }

        if (attacked) {
            lastAttackTimeMs = now
        }
    }

    // ============ 辅助 ============

    /**
     * 解析 native 返回的实体扁平数组为 List<GameEntity>。
     *
     * 数组格式（每实体 14 个 float）：
     *   [id_lo, id_hi, type, x, y, z, yaw, pitch, health, maxHealth, armor,
     *    isOnGround, isLocalPlayer, isAlive]
     */
    private fun parseEntities(data: FloatArray): List<GameEntity> {
        if (data.isEmpty()) return emptyList()
        val stride = 14
        val count = data.size / stride
        if (count == 0) return emptyList()
        val result = ArrayList<GameEntity>(count)
        for (i in 0 until count) {
            val base = i * stride
            try {
                // entityId 由两个 32 位拼成 64 位
                val idLo = data[base + 0].toLong().and(0xFFFFFFFFL)
                val idHi = data[base + 1].toLong().and(0xFFFFFFFFL).shl(32)
                val entityId = idLo or idHi

                val type = EntityType.entries.getOrElse(data[base + 2].toInt()) { EntityType.OTHER }

                result.add(GameEntity(
                    entityId = entityId,
                    type = type,
                    name = "Entity#$entityId",  // native stub 暂未传名，后续可扩展
                    x = data[base + 3].toDouble(),
                    y = data[base + 4].toDouble(),
                    z = data[base + 5].toDouble(),
                    yaw = data[base + 6],
                    pitch = data[base + 7],
                    health = data[base + 8],
                    maxHealth = data[base + 9],
                    armor = data[base + 10].toInt(),
                    isOnGround = data[base + 11] != 0f,
                    isLocalPlayer = data[base + 12] != 0f,
                    isAlive = data[base + 13] != 0f
                ))
            } catch (_: Throwable) {
                // 跳过损坏的条目
            }
        }
        return result
    }

    /**
     * 计算瞄准目标所需的 yaw/pitch。
     * Minecraft 坐标系：
     *   - yaw=0 时朝南 (+Z)
     *   - yaw 增加顺时针（→ +X 时 yaw=-90）
     *   - pitch > 0 时向下看
     */
    private fun computeAimAngle(px: Double, py: Double, pz: Double, target: GameEntity): Pair<Float, Float> {
        val dx = target.x - px
        val dy = target.y - py
        val dz = target.z - pz

        // 朝向目标水平方向
        val yaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
        // 计算到目标头部的俯仰角
        val horizontalDist = kotlin.math.sqrt(dx * dx + dz * dz)
        val pitch = (-Math.toDegrees(kotlin.math.atan2(dy, horizontalDist))).toFloat()
        return yaw to pitch
    }

    private fun parseBlacklist(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
