// OpenWorldBox Hook 引擎封装
//
// 对 Dobby 的薄封装，提供：
//   - 统一的 hook 安装接口
//   - 自动保存原始函数指针（用于调用原函数）
//   - 错误处理和日志
//
// 用法：
//   void* orig = nullptr;
//   HookEngine::hook(target_addr, my_func, &orig);
//   // 之后通过 orig 调用原函数

#pragma once

#include <cstdint>
#include <cstring>
#include "dobby.h"

namespace owb {

class HookEngine {
public:
    /**
     * 安装 inline hook
     *
     * @param target     目标函数地址
     * @param replacement 替换函数地址
     * @param original   [out] 接收原始函数的 trampoline 地址（用于调用原函数）
     * @return true 表示安装成功
     */
    static bool hook(void* target, void* replacement, void** original) {
        if (!target || !replacement) return false;
        int ret = DobbyHook(target, replacement, original);
        if (ret != 0) {
            logError("DobbyHook failed", target, replacement);
            return false;
        }
        return true;
    }

    /**
     * 卸载 hook
     */
    static bool unhook(void* target) {
        if (!target) return false;
        return DobbyDestroy(target) == 0;
    }

    /**
     * 通过符号名从指定模块解析函数地址
     *
     * @param moduleName 模块路径或名字（如 "libminecraftpe.so"）
     * @param symbol     符号名
     * @return 函数地址，失败返回 nullptr
     */
    static void* resolveSymbol(const char* moduleName, const char* symbol);

    /**
     * 获取模块基址
     */
    static void* getModuleBase(const char* moduleName);

    /**
     * 获取模块在内存中的范围（基址 + 大小）
     */
    static bool getModuleRange(const char* moduleName, void** base, size_t* size);

private:
    static void logError(const char* msg, void* target, void* replacement);
};

} // namespace owb
