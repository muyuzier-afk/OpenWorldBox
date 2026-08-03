// OpenWorldBox Actor 访问层
//
// 封装对 Minecraft Actor 类（实体）的内存读取。
//
// Actor 在游戏内存中是一个对象，包含位置、朝向、血量等字段。
// 由于游戏版本不同，字段偏移会变化，这里通过 [game_offsets.h] 集中管理。
//
// 当前所有偏移为 0（未知），读取会返回 0。
// 待填入真实偏移后即可工作。

#pragma once

#include <cstdint>
#include "game_offsets.h"

namespace owb {

// Actor 类的指针别名
using ActorPtr = void*;
using LocalPlayerPtr = void*;
using LevelPtr = void*;

// 实体类型枚举（与 Kotlin EntityType 对应）
enum class EntityType : int {
    PLAYER       = 1,
    HOSTILE_MOB  = 2,
    PASSIVE_MOB  = 3,
    LOCAL_PLAYER = 4,
    OTHER        = 99
};

class ActorAccess {
public:
    // ============ 位置 ============
    static float getPosX(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::POS_X);
    }
    static float getPosY(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::POS_Y);
    }
    static float getPosZ(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::POS_Z);
    }

    // ============ 朝向 ============
    static float getYaw(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::YAW);
    }
    static float getPitch(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::PITCH);
    }

    // ============ 血量 ============
    static float getHealth(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::HEALTH);
    }
    static float getMaxHealth(ActorPtr actor) {
        if (!actor) return 0;
        return readFloat(actor, ActorOffsets::MAX_HEALTH);
    }

    // ============ 护甲 ============
    static int getArmor(ActorPtr actor) {
        if (!actor) return 0;
        return readInt(actor, ActorOffsets::ARMOR);
    }

    // ============ 实体 ID ============
    static int64_t getEntityId(ActorPtr actor) {
        if (!actor) return 0;
        return readInt64(actor, ActorOffsets::ENTITY_ID);
    }

    // ============ 类型 ============
    static EntityType getEntityType(ActorPtr actor) {
        if (!actor) return EntityType::OTHER;
        int typeId = readInt(actor, ActorOffsets::ENTITY_TYPE_ID);
        switch (typeId) {
            case 1:  return EntityType::PLAYER;
            case 2:  return EntityType::HOSTILE_MOB;
            case 3:  return EntityType::PASSIVE_MOB;
            case 4:  return EntityType::LOCAL_PLAYER;
            default: return EntityType::OTHER;
        }
    }

    // ============ 标志位 ============
    static bool isOnGround(ActorPtr actor) {
        if (!actor) return false;
        uint32_t flags = readUInt(actor, ActorOffsets::FLAGS);
        return (flags & ActorOffsets::FLAG_ON_GROUND) != 0;
    }
    static bool isAlive(ActorPtr actor) {
        if (!actor) return false;
        uint32_t flags = readUInt(actor, ActorOffsets::FLAGS);
        return (flags & ActorOffsets::FLAG_ALIVE) != 0;
    }

private:
    // ============ 内存读取原语 ============
    static float readFloat(ActorPtr actor, int offset) {
        if (offset == 0) return 0;
        return *reinterpret_cast<float*>((uint8_t*)actor + offset);
    }
    static int readInt(ActorPtr actor, int offset) {
        if (offset == 0) return 0;
        return *reinterpret_cast<int*>((uint8_t*)actor + offset);
    }
    static int64_t readInt64(ActorPtr actor, int offset) {
        if (offset == 0) return 0;
        return *reinterpret_cast<int64_t*>((uint8_t*)actor + offset);
    }
    static uint32_t readUInt(ActorPtr actor, int offset) {
        if (offset == 0) return 0;
        return *reinterpret_cast<uint32_t*>((uint8_t*)actor + offset);
    }
};

} // namespace owb
