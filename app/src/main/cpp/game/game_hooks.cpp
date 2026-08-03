// OpenWorldBox Game Hooks 实现
//
// 当前为"安全降级"实现：
//   - 偏移全为 0 → 所有读取返回默认值（0）
//   - 函数地址未解析 → attackEntity 返回 false
//   - 不会崩溃，但 KillAura 看不到任何实体
//
// 接入真实游戏后，只需：
//   1. 在 game_offsets.h 填入偏移（或 patterns 填入 pattern）
//   2. install() 会自动通过 dlsym 或 pattern scan 解析函数
//   3. 读取函数自动工作

#include "game_hooks.h"
#include "hook/hook_engine.h"
#include "hook/pattern_scan.h"
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>

#define LOG_TAG "OpenWorldBox-GameHooks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace owb {

// 静态成员定义
bool            GameHooks::s_hooksInstalled   = false;
LocalPlayerPtr  GameHooks::s_localPlayer      = nullptr;
LevelPtr        GameHooks::s_level            = nullptr;
void*           GameHooks::s_funcPlayerAttack = nullptr;
void*           GameHooks::s_funcLevelGetEntities = nullptr;
void*           GameHooks::s_funcGetLocalPlayer   = nullptr;

// ============ 安装 hooks ============

bool GameHooks::install() {
    if (s_hooksInstalled) return true;
    LOGI("开始安装 game hooks...");

    // 1. 确认游戏库已加载
    void* gameBase = HookEngine::getModuleBase(GAME_LIBRARY);
    if (!gameBase) {
        LOGW("游戏库 %s 未加载，hooks 暂不安装", GAME_LIBRARY);
        LOGW("（这是正常的——Xposed 模块在游戏 onCreate 之前可能拿到这个时机）");
        return false;
    }
    LOGI("游戏库基址: %p", gameBase);

    // 2. 解析关键函数
    // 优先用 dlsym（如果游戏库未 strip）
    s_funcPlayerAttack = resolveGameFunction(
        "_ZN6Player6attackEP6Entity",
        GamePatterns::PLAYER_ATTACK,
        0
    );

    s_funcLevelGetEntities = resolveGameFunction(
        "_ZN5Level12getEntitiesEv",
        GamePatterns::LEVEL_GET_ENTITIES,
        0
    );

    s_funcGetLocalPlayer = resolveGameFunction(
        "_ZN9Minecraft14getLocalPlayerEv",
        GamePatterns::GET_LOCAL_PLAYER,
        0
    );

    LOGI("函数解析结果:");
    LOGI("  Player::attack     = %p", s_funcPlayerAttack);
    LOGI("  Level::getEntities = %p", s_funcLevelGetEntities);
    LOGI("  getLocalPlayer     = %p", s_funcGetLocalPlayer);

    if (!s_funcPlayerAttack && !s_funcLevelGetEntities) {
        LOGW("未解析到任何关键函数。KillAura 将无法工作。");
        LOGW("请在 game_offsets.h / GamePatterns 中填入真实偏移/pattern。");
        // 不算失败，仍标记为已安装（避免重复尝试）
    }

    s_hooksInstalled = true;
    LOGI("Game hooks 安装完成");
    return true;
}

void GameHooks::uninstall() {
    if (!s_hooksInstalled) return;
    // 这里应该 unhook 所有已安装的 hook
    // 当前未实际安装任何 hook，所以无需卸载
    s_hooksInstalled = false;
    s_localPlayer = nullptr;
    s_level = nullptr;
    LOGI("Game hooks 已卸载");
}

// ============ Kotlin NativeBridge 实现入口 ============

bool GameHooks::getLocalPlayerPos(float out[3]) {
    out[0] = out[1] = out[2] = 0.0f;
    if (!s_localPlayer) return false;
    // 通过 LocalPlayer 偏移读取
    out[0] = ActorAccess::getPosX(s_localPlayer);
    out[1] = ActorAccess::getPosY(s_localPlayer);
    out[2] = ActorAccess::getPosZ(s_localPlayer);
    return true;
}

bool GameHooks::getLocalPlayerRotation(float out[2]) {
    out[0] = out[1] = 0.0f;
    if (!s_localPlayer) return false;
    out[0] = ActorAccess::getYaw(s_localPlayer);
    out[1] = ActorAccess::getPitch(s_localPlayer);
    return true;
}

int GameHooks::queryEntities(std::vector<float>& out) {
    out.clear();
    if (!s_level) return 0;

    // 从 Level 读取实体列表
    // 真实实现：
    //   auto& vec = *reinterpret_cast<std::vector<Actor*>*>(
    //       (uint8_t*)s_level + LevelOffsets::ENTITIES_VECTOR_PTR);
    //   for (auto* actor : vec) { ... }
    //
    // 当前 ENTITIES_VECTOR_PTR = 0，无法读取，返回空

    if (LevelOffsets::ENTITIES_VECTOR_PTR == 0) {
        return 0;
    }

    // 占位：实际遍历逻辑（待填入偏移后激活）
    // std::vector<Actor*>* entityVec = ...;
    // for (auto* actor : *entityVec) {
    //     if (!actor) continue;
    //     float entityData[14];
    //     packEntity(actor, entityData);
    //     out.insert(out.end(), entityData, entityData + 14);
    // }
    return (int)(out.size() / 14);
}

bool GameHooks::attackEntity(int64_t entityId, bool swingHand) {
    if (!s_funcPlayerAttack || !s_localPlayer) {
        return false;
    }

    // 真实实现：
    //   1. 通过 entityId 在 s_level 的实体列表中找到 Actor*
    //   2. 调用 Player::attack(s_localPlayer, targetActor)
    //
    // 函数签名（C++ mangling）：
    //   void Player::attack(Entity*);
    //   调用方式：((void(*)(void*, void*))s_funcPlayerAttack)(s_localPlayer, target);

    (void)entityId;
    (void)swingHand;
    LOGI("[未实现] attackEntity: id=%lld", (long long)entityId);
    return false;
}

bool GameHooks::setRotationSilent(float yaw, float pitch) {
    if (!s_localPlayer) return false;

    // 真实实现：
    //   直接修改 LocalPlayer 的 yaw/pitch 字段（仅影响网络包，不影响视角）
    //   或 hook 网络发包函数，在发送 MoveActorDeltaPacket 时替换 yaw/pitch

    (void)yaw;
    (void)pitch;
    return false;
}

bool GameHooks::isLineOfSightClear(double x, double y, double z) {
    // 真实实现：
    //   调用 Level::hasLineOfSight(from, to) 或自己实现 raycast
    //   当前默认返回 true（无遮挡）
    (void)x; (void)y; (void)z;
    return true;
}

// ============ 内部辅助 ============

void* GameHooks::resolveGameFunction(const char* symbol,
                                     const char* pattern,
                                     int patternOffset) {
    // 1. 优先用 dlsym
    if (symbol && symbol[0] != '\0') {
        void* addr = HookEngine::resolveSymbol(GAME_LIBRARY, symbol);
        if (addr) {
            LOGI("dlsym 解析成功: %s = %p", symbol, addr);
            return addr;
        }
    }

    // 2. 兜底用 pattern scan
    if (pattern && pattern[0] != '?' || (pattern && pattern[0] != '\0')) {
        // 检查 pattern 是否是占位符（全是 ??）
        bool isPlaceholder = true;
        for (const char* p = pattern; *p; p++) {
            if (*p != ' ' && *p != '\t' && *p != '?') {
                isPlaceholder = false;
                break;
            }
        }
        if (!isPlaceholder) {
            void* addr = PatternScanner::find(GAME_LIBRARY, pattern, patternOffset);
            if (addr) {
                LOGI("pattern scan 解析成功: %s = %p", pattern, addr);
                return addr;
            }
        }
    }

    return nullptr;
}

void GameHooks::onGameTick(void* thisPtr) {
    // 通过 hook 游戏的 tick 函数捕获关键对象指针
    // 例如：hook Level::tick，每次调用时 s_level = thisPtr
    // 当前未实际安装 hook
    (void)thisPtr;
}

} // namespace owb
