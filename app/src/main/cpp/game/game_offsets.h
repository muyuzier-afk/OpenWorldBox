// OpenWorldBox 游戏偏移表
//
// 这里定义了 Minecraft PE (网易 x19) 关键类/函数的内存偏移。
//
// 由于游戏会随版本更新而改变布局，所有偏移集中在这一处维护。
// 偏移值默认为 0（未知），需要通过逆向工具（IDA/Ghidra）填入。
//
// 偏移获取方法：
//   1. 静态分析 libminecraftpe.so，找到对应符号（如果未 strip）
//   2. 用 PatternScanner 在内存中搜索函数特征
//   3. 通过 dlsym 解析符号
//
// 当前所有偏移为占位值，运行时会通过 PatternScanner 兜底查找。

#pragma once

#include <cstdint>

namespace owb {

// 目标游戏模块名
constexpr const char* GAME_LIBRARY = "libminecraftpe.so";

// ============ Actor 字段偏移 ============
//
// Actor 是 Minecraft 中所有实体的基类（玩家、怪物、动物等）。
// 这些偏移用于直接读取内存中的实体数据。
struct ActorOffsets {
    // 实体在世界中的位置（Vec3: x, y, z）
    static constexpr int POS_X = 0;        // 待填：通常在 Actor 类前 0x100 内
    static constexpr int POS_Y = 4;
    static constexpr int POS_Z = 8;

    // 朝向（度）
    static constexpr int YAW   = 0;        // 待填
    static constexpr int PITCH = 0;

    // 血量
    static constexpr int HEALTH    = 0;    // 待填
    static constexpr int MAX_HEALTH = 0;

    // 护甲值
    static constexpr int ARMOR = 0;

    // 实体 ID（uniqueID，通常是 int64）
    static constexpr int ENTITY_ID = 0;

    // 实体类型标识（用于区分玩家/怪物/动物）
    static constexpr int ENTITY_TYPE_ID = 0;

    // 标志位（onGround, alive 等）
    static constexpr int FLAGS = 0;
    static constexpr uint32_t FLAG_ON_GROUND   = 0;
    static constexpr uint32_t FLAG_ALIVE       = 0;
};

// ============ LocalPlayer 字段偏移 ============
//
// LocalPlayer 是当前玩家，继承自 Player → Mob → Actor。
// 这些偏移用于读取"我自己"的信息。
struct LocalPlayerOffsets {
    // LocalPlayer 单例指针的偏移（在 GameMode 或 Minecraft 类中）
    static constexpr int LOCAL_PLAYER_PTR = 0;
};

// ============ Level 字段偏移 ============
//
// Level 是游戏世界，包含所有实体。
// 用于遍历实体列表。
struct LevelOffsets {
    // 实体列表 std::vector<Actor*> 的起始地址偏移
    static constexpr int ENTITIES_VECTOR_PTR = 0;
    static constexpr int ENTITIES_VECTOR_SIZE = 0;
};

// ============ GameMode / Player 攻击相关 ============
//
// Player::attack(Entity*) 用于发起攻击。
struct PlayerOffsets {
    // attack 函数的 vtable 索引（Player 类的虚函数表）
    static constexpr int ATTACK_VTABLE_INDEX = 0;
};

// ============ Pattern Scan 兜底 ============
//
// 当偏移未知时，用字节模式扫描定位函数。
// 这些 pattern 需要从游戏 .so 中提取（IDA 中右键 → Pattern）。
// 当前为占位符，需要根据实际版本填入。
struct GamePatterns {
    // Level::getEntities 返回值的特征
    static constexpr const char* LEVEL_GET_ENTITIES = "?? ?? ?? ?? ?? ??";

    // Player::attack 函数特征
    static constexpr const char* PLAYER_ATTACK = "?? ?? ?? ?? ?? ??";

    // LocalPlayer 单例获取
    static constexpr const char* GET_LOCAL_PLAYER = "?? ?? ?? ?? ?? ??";
};

// ============ 游戏版本检测 ============
//
// 通过特定字符串识别游戏版本，便于按版本切换偏移表。
struct GameVersion {
    // 在 .so 中查找的版本字符串
    static constexpr const char* VERSION_STRING_PATTERN = "1.21.";  // 通用前缀

    // 已知版本枚举
    enum class Known {
        UNKNOWN,
        V1_21_50,    // 1.21.50
        V1_21_40,    // 1.21.40
        V1_21_30,    // 1.21.30
    };

    static Known detect();
};

} // namespace owb
