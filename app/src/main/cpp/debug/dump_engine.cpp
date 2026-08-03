// OpenWorldBox 调试 Dump 引擎实现

#include "dump_engine.h"
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <cstring>
#include <cctype>
#include <cstdio>
#include <fstream>
#include <sstream>

#define LOG_TAG "OpenWorldBox-Dump"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace owb {

// ============ 模块枚举 ============

// dl_iterate_phdr 回调上下文
struct EnumCtx {
    int count;
    const char* highlight;  // 需要重点标记的模块名
};

static int enumCallback(struct dl_phdr_info* info, size_t sz, void* data) {
    (void)sz;
    auto* ctx = static_cast<EnumCtx*>(data);
    if (!info->dlpi_name || info->dlpi_name[0] == '\0') return 0;

    ctx->count++;

    // 计算模块总大小（遍历 PT_LOAD 段）
    size_t totalSize = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* phdr = &info->dlpi_phdr[i];
        if (phdr->p_type == PT_LOAD) {
            size_t end = phdr->p_vaddr + phdr->p_memsz;
            if (end > totalSize) totalSize = end;
        }
    }

    const char* name = info->dlpi_name;
    const char* slash = strrchr(name, '/');
    const char* baseName = slash ? slash + 1 : name;

    bool isHighlight = ctx->highlight &&
        (strstr(name, ctx->highlight) != nullptr ||
         strcmp(baseName, ctx->highlight) == 0);

    if (isHighlight) {
        LOGI(">>> [GAME] %s", name);
        LOGI("    base = %p, size = 0x%zx (%zu bytes)",
             (void*)info->dlpi_addr, totalSize, totalSize);
        // 输出各段
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const ElfW(Phdr)* phdr = &info->dlpi_phdr[i];
            if (phdr->p_type == PT_LOAD) {
                LOGI("    LOAD: vaddr=0x%lx memsz=0x%lx flags=0x%x",
                     (long)phdr->p_vaddr, (long)phdr->p_memsz, phdr->p_flags);
            }
        }
    } else {
        // 普通模块只打印一行
        LOGI("[%3d] base=%p size=0x%-8zx %s",
             ctx->count, (void*)info->dlpi_addr, totalSize, name);
    }
    return 0;
}

int DumpEngine::dumpLoadedModules() {
    LOGI("========== 已加载模块列表 ==========");
    EnumCtx ctx{0, "libminecraftpe"};
    dl_iterate_phdr(enumCallback, &ctx);
    LOGI("========== 共 %d 个模块 ==========", ctx.count);
    return ctx.count;
}

// getModuleInfo 的回调上下文
struct InfoCtx {
    const char* target;
    void*  base;
    size_t size;
    bool   found;
};

static int infoCallback(struct dl_phdr_info* info, size_t sz, void* data) {
    (void)sz;
    auto* ctx = static_cast<InfoCtx*>(data);
    if (!info->dlpi_name) return 0;

    const char* name = info->dlpi_name;
    const char* slash = strrchr(name, '/');
    const char* baseName = slash ? slash + 1 : name;

    if (strstr(name, ctx->target) != nullptr ||
        strcmp(baseName, ctx->target) == 0) {
        ctx->base = (void*)info->dlpi_addr;
        ctx->size = 0;
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const ElfW(Phdr)* phdr = &info->dlpi_phdr[i];
            if (phdr->p_type == PT_LOAD) {
                size_t end = phdr->p_vaddr + phdr->p_memsz;
                if (end > ctx->size) ctx->size = end;
            }
        }
        ctx->found = true;
        return 1;  // 找到了，停止
    }
    return 0;
}

bool DumpEngine::getModuleInfo(const char* moduleName,
                               void** base, size_t* size) {
    InfoCtx ctx{moduleName, nullptr, 0, false};
    dl_iterate_phdr(infoCallback, &ctx);
    if (ctx.found) {
        if (base) *base = ctx.base;
        if (size) *size = ctx.size;
    }
    return ctx.found;
}

// ============ 符号解析 ============

void* DumpEngine::resolveSymbol(const char* symbol) {
    if (!symbol) return nullptr;

    // 先用 RTLD_DEFAULT 全局查找
    void* addr = dlsym(RTLD_DEFAULT, symbol);
    if (addr) {
        LOGI("[dlsym] ✓ %s = %p", symbol, addr);
        return addr;
    }

    // 再尝试从游戏库单独 dlopen + dlsym
    void* handle = dlopen("libminecraftpe.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen("libminecraftpe.so", RTLD_NOW);
    }
    if (handle) {
        addr = dlsym(handle, symbol);
        if (addr) {
            LOGI("[dlsym] ✓ %s = %p (via libminecraftpe.so)", symbol, addr);
            return addr;
        }
    }

    LOGI("[dlsym] ✗ %s (not found)", symbol);
    return nullptr;
}

void DumpEngine::resolveSymbolBatch(const char** symbols) {
    if (!symbols) return;
    LOGI("========== 批量符号解析 ==========");
    int found = 0, total = 0;
    for (int i = 0; symbols[i] != nullptr; i++) {
        total++;
        if (resolveSymbol(symbols[i]) != nullptr) found++;
    }
    LOGI("========== 解析完成: %d/%d 成功 ==========", found, total);
}

// ============ 内存 dump ============

bool DumpEngine::isReadable(const void* addr, size_t size) {
    if (!addr || size == 0) return false;
    // 通过 /proc/self/maps 检查（简化：直接 try-read 也行，
    // 但 maps 更安全，避免 SIGSEGV）
    std::ifstream f("/proc/self/maps");
    if (!f) return false;

    uintptr_t start = (uintptr_t)addr;
    uintptr_t end = start + size;

    std::string line;
    while (std::getline(f, line)) {
        // 行格式: start-end perms offset ...
        uintptr_t s, e;
        char perms[8] = {0};
        if (sscanf(line.c_str(), "%lx-%lx %7s", &s, &e, perms) != 3) continue;
        if (s <= start && end <= e && perms[0] == 'r') {
            return true;
        }
    }
    return false;
}

void DumpEngine::logHex(const void* addr, size_t size) {
    auto* p = (const uint8_t*)addr;
    char buf[256];
    int pos = 0;
    for (size_t i = 0; i < size; i += 16) {
        pos = snprintf(buf, sizeof(buf), "%p: ", p + i);
        // hex
        for (size_t j = 0; j < 16 && (i + j) < size; j++) {
            pos += snprintf(buf + pos, sizeof(buf) - pos, "%02x ", p[i + j]);
        }
        // 补齐对齐
        for (size_t j = (size - i < 16 ? size - i : 16); j < 16; j++) {
            pos += snprintf(buf + pos, sizeof(buf) - pos, "   ");
        }
        // ASCII
        pos += snprintf(buf + pos, sizeof(buf) - pos, " |");
        for (size_t j = 0; j < 16 && (i + j) < size; j++) {
            uint8_t c = p[i + j];
            buf[pos++] = (c >= 32 && c < 127) ? c : '.';
        }
        buf[pos++] = '|';
        buf[pos] = '\0';
        LOGI("%s", buf);
    }
}

void DumpEngine::dumpMemoryAt(const void* addr, size_t size) {
    if (!addr) {
        LOGW("dumpMemoryAt: addr=nullptr");
        return;
    }
    if (!isReadable(addr, size)) {
        LOGW("dumpMemoryAt: %p (size=%zu) 不可读", addr, size);
        return;
    }
    LOGI("---------- memory dump %p size=%zu ----------", addr, size);
    logHex(addr, size);
    LOGI("---------- end ----------");
}

void DumpEngine::dumpModuleMemory(const char* moduleName,
                                  size_t offset, size_t size) {
    void* base = nullptr;
    size_t modSize = 0;
    if (!getModuleInfo(moduleName, &base, &modSize)) {
        LOGW("dumpModuleMemory: 模块 %s 未加载", moduleName);
        return;
    }
    if (offset >= modSize) {
        LOGW("dumpModuleMemory: offset=0x%zx 超出模块大小 0x%zx", offset, modSize);
        return;
    }
    size_t dumpSize = size;
    if (offset + dumpSize > modSize) {
        dumpSize = modSize - offset;
        LOGW("dumpModuleMemory: 截断到 0x%zx 字节", dumpSize);
    }
    void* target = (uint8_t*)base + offset;
    LOGI("=== %s + 0x%zx (base=%p) ===", moduleName, offset, base);
    dumpMemoryAt(target, dumpSize);
}

// ============ 字符串扫描 ============

int DumpEngine::dumpStringSearch(const char* moduleName,
                                 const char* keyword, int maxResults) {
    if (!keyword || !moduleName) return 0;

    void* base = nullptr;
    size_t size = 0;
    if (!getModuleInfo(moduleName, &base, &size)) {
        LOGW("dumpStringSearch: 模块 %s 未加载", moduleName);
        return 0;
    }

    LOGI("=== 在 %s 中扫描字符串 \"%s\" (max %d) ===",
         moduleName, keyword, maxResults);

    auto* p = (const uint8_t*)base;
    size_t kwLen = strlen(keyword);
    int found = 0;

    // 简单滑窗搜索
    for (size_t i = 0; i + kwLen < size && found < maxResults; i++) {
        if (memcmp(p + i, keyword, kwLen) != 0) continue;

        // 提取周围 ASCII 上下文（前后各 32 字节，遇到非可打印停止）
        size_t strStart = i;
        // 向前找字符串起点
        while (strStart > 0 && p[strStart - 1] >= 32 && p[strStart - 1] < 127) {
            strStart--;
            if (i - strStart > 64) break;  // 限制回溯长度
        }
        size_t strEnd = i + kwLen;
        while (strEnd < size && p[strEnd] >= 32 && p[strEnd] < 127) {
            strEnd++;
            if (strEnd - i > 256) break;  // 限制前向长度
        }

        // 输出
        char buf[300];
        size_t copyLen = strEnd - strStart;
        if (copyLen >= sizeof(buf)) copyLen = sizeof(buf) - 1;
        memcpy(buf, p + strStart, copyLen);
        buf[copyLen] = '\0';

        LOGI("[%2d] off=0x%zx: %s", found + 1, strStart, buf);
        found++;

        // 跳过当前字符串，避免重复匹配
        i = strEnd;
    }

    if (found == 0) {
        LOGI("未找到包含 \"%s\" 的字符串", keyword);
    } else {
        LOGI("=== 找到 %d 个匹配 ===", found);
    }
    return found;
}

// ============ 综合扫描 ============

void DumpEngine::runFullScan() {
    LOGI("############ OpenWorldBox 全量扫描开始 ############");

    // 1. 模块列表
    dumpLoadedModules();

    // 2. 模块信息
    void* base = nullptr;
    size_t size = 0;
    if (getModuleInfo("libminecraftpe.so", &base, &size)) {
        LOGI("libminecraftpe.so: base=%p size=0x%zx (%zu MB)",
             base, size, size / (1024 * 1024));
    } else {
        LOGW("libminecraftpe.so 未加载，扫描终止");
        return;
    }

    // 3. 候选符号批量 dlsym
    // 这些是 Minecraft PE 1.21.50 中 KillAura 最可能用到的符号
    static const char* candidateSymbols[] = {
        // Player 攻击
        "_ZN6Player6attackEP6Entity",
        "_ZN6Player6attackER6Entity",
        "_ZNK6Player6attackER6Entity",
        // Level 实体遍历
        "_ZN5Level12getEntitiesEv",
        "_ZNK5Level12getEntitiesEv",
        "_ZN5Level14getRuntimeActorEv",
        // LocalPlayer 单例
        "_ZN9Minecraft14getLocalPlayerEv",
        "_ZNK9Minecraft14getLocalPlayerEv",
        // Actor 位置
        "_ZNK5Actor11getPosDeltaEv",
        "_ZNK5Actor7getPosEv",
        "_ZNK5Actor13getRotationEv",
        // Mob 血量
        "_ZNK3Mob5getHealthEv",
        "_ZNK3Mob12getMaxHealthEv",
        // 视线检测
        "_ZN5Level14hasLineOfSightERK5ActorS2_",
        "_ZNK5Actor14hasLineOfSightERKS_",
        nullptr
    };
    resolveSymbolBatch(candidateSymbols);

    // 4. 关键字符串扫描
    // 这些字符串通常出现在 RTTI、调试日志、assert 中
    static const char* keywords[] = {
        "Player::attack",
        "Level::getEntities",
        "Minecraft::getLocalPlayer",
        "LocalPlayer",
        "Level::tick",
        "Minecraft::tick",
        "Player",
        "Level",
        nullptr
    };
    for (int i = 0; keywords[i] != nullptr; i++) {
        dumpStringSearch("libminecraftpe.so", keywords[i], 10);
    }

    LOGI("############ 全量扫描完成 ############");
    LOGI("提示：在 logcat 中过滤 tag=OpenWorldBox-Dump 查看完整结果");
    LOGI("提示：若 dlsym 全部失败，说明符号被 strip，需用 pattern scan");
    LOGI("提示：字符串扫描结果中的 offset 可填入 GamePatterns 用于 pattern 提取");
}

} // namespace owb
