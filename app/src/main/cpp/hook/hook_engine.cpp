// OpenWorldBox Hook 引擎实现

#include "hook_engine.h"
#include <dlfcn.h>
#include <android/log.h>
#include <link.h>
#include <cstring>

#define LOG_TAG "OpenWorldBox-Hook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace owb {

void HookEngine::logError(const char* msg, void* target, void* replacement) {
    LOGE("%s: target=%p, replacement=%p", msg, target, replacement);
}

void* HookEngine::resolveSymbol(const char* moduleName, const char* symbol) {
    // 先尝试 dlopen
    void* handle = dlopen(moduleName, RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        // 如果模块名是简写（如 "libminecraftpe.so"），尝试完整路径
        handle = dlopen(moduleName, RTLD_NOW);
    }
    if (!handle) {
        LOGE("dlopen %s failed: %s", moduleName, dlerror());
        return nullptr;
    }
    void* sym = dlsym(handle, symbol);
    if (!sym) {
        LOGE("dlsym %s in %s failed: %s", symbol, moduleName, dlerror());
    }
    // 注意：不要 dlclose，否则符号可能失效
    return sym;
}

void* HookEngine::getModuleBase(const char* moduleName) {
    void* base = nullptr;
    size_t size = 0;
    if (getModuleRange(moduleName, &base, &size)) {
        return base;
    }
    return nullptr;
}

// dl_iterate_phdr 回调上下文
struct ModuleSearchCtx {
    const char* targetName;
    void*       base;
    size_t      size;
    bool        found;
};

static int dlIterateCallback(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    auto* ctx = static_cast<ModuleSearchCtx*>(data);
    if (!info->dlpi_name) return 0;

    // 检查模块名是否匹配（支持简写和完整路径）
    const char* name = info->dlpi_name;
    const char* slash = strrchr(name, '/');
    const char* baseName = slash ? slash + 1 : name;

    bool match = (strstr(name, ctx->targetName) != nullptr) ||
                 (strcmp(baseName, ctx->targetName) == 0);
    if (!match) return 0;

    ctx->base = (void*)info->dlpi_addr;
    ctx->size = 0;
    // 计算模块大小：遍历所有 PT_LOAD 段
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* phdr = &info->dlpi_phdr[i];
        if (phdr->p_type == PT_LOAD) {
            size_t end = phdr->p_vaddr + phdr->p_memsz;
            if (end > ctx->size) ctx->size = end;
        }
    }
    ctx->found = true;
    return 1;  // 找到了，停止迭代
}

bool HookEngine::getModuleRange(const char* moduleName, void** base, size_t* size) {
    ModuleSearchCtx ctx{moduleName, nullptr, 0, false};
    dl_iterate_phdr(dlIterateCallback, &ctx);
    if (ctx.found) {
        if (base) *base = ctx.base;
        if (size) *size = ctx.size;
        LOGI("模块 %s: base=%p, size=%zu", moduleName, ctx.base, ctx.size);
        return true;
    }
    LOGE("未找到模块 %s", moduleName);
    return false;
}

} // namespace owb
