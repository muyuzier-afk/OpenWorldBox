// OpenWorldBox Game Hooks
//
// 这是连接 native hook 与 Kotlin NativeBridge 的核心。
//
// 职责：
//   1. 在 nativeInit 时安装 hooks（捕获 LocalPlayer / Level / Player::attack）
//   2. 提供 Kotlin NativeBridge.* 系列函数的真实实现：
//      - nativeGetLocalPlayerPos
//      - nativeGetLocalPlayerRotation
//      - nativeQueryEntities
//      - nativeAttackEntity
//      - nativeSetRotationSilent
//      - nativeIsLineOfSightClear
//
// 当前状态：
//   - Hook 框架已就绪（Dobby）
//   - 偏移表为占位（待填入真实值）
//   - 当偏移为 0 时，所有读取返回默认值，不崩溃
//   - 当攻击函数地址未解析时，attackEntity 返回 false
//
// 接入真实游戏的步骤（详见 README.md）：
//   1. 在 IDA 中打开 libminecraftpe.so
//   2. 找到关键函数/字段的偏移
//   3. 填入 game_offsets.h
//   4. 或在 patterns 中填入 pattern scan 字符串
//   5. 重新编译

#pragma once

#include <cstdint>
#include <vector>
#include "game/actor_access.h"
#include "game/game_offsets.h"

namespace owb {

class GameHooks {
public:
    /**
     * 安装所有 hooks。
     * 在 nativeInit 中调用一次。
     *
     * @return true 表示至少安装成功一个 hook
     */
    static bool install();

    /**
     * 卸载所有 hooks。
     */
    static void uninstall();

    // ============ Kotlin NativeBridge 实现入口 ============
    // 这些函数由 jni_bridge.cpp 调用

    /** 读取本地玩家位置，写入 out[3] */
    static bool getLocalPlayerPos(float out[3]);

    /** 读取本地玩家朝向，写入 out[2] = {yaw, pitch} */
    static bool getLocalPlayerRotation(float out[2]);

    /**
     * 查询所有实体，扁平打包到 out。
     * 每实体 14 个 float（与 Kotlin 协议一致）：
     *   [id_lo, id_hi, type, x, y, z, yaw, pitch, health, maxHealth, armor,
     *    isOnGround, isLocalPlayer, isAlive]
     *
     * @return 实体数量
     */
    static int queryEntities(std::vector<float>& out);

    /** 攻击指定实体 */
    static bool attackEntity(int64_t entityId, bool swingHand);

    /** 静默设置朝向（仅发包，不实际转动玩家视角） */
    static bool setRotationSilent(float yaw, float pitch);

    /** 检查玩家与目标之间是否有方块遮挡 */
    static bool isLineOfSightClear(double x, double y, double z);

private:
    // ============ 已安装的 hooks 状态 ============
    static bool s_hooksInstalled;

    // 捕获到的关键对象指针（通过 hook 获取）
    static LocalPlayerPtr s_localPlayer;
    static LevelPtr       s_level;

    // 关键函数地址（通过 dlsym 或 pattern scan 获取）
    static void* s_funcPlayerAttack;     // Player::attack
    static void* s_funcLevelGetEntities; // Level::getEntities
    static void* s_funcGetLocalPlayer;   // 获取 LocalPlayer 单例

    // ============ 内部辅助 ============
    static void* resolveGameFunction(const char* symbol,
                                     const char* pattern,
                                     int patternOffset);

    // Hook 回调：捕获 LocalPlayer
    // 例如 hook Minecraft::tick 或 Level::tick，在调用时保存 this 指针
    static void onGameTick(void* thisPtr);
};

} // namespace owb
