// OpenWorldBox 调试 Dump 引擎
//
// 在游戏进程内运行时枚举已加载模块、解析符号、dump 内存、扫描字符串，
// 用于辅助逆向分析网易 x19 libminecraftpe.so（该 .so 被符号加密，
// 静态分析拿不到符号，但运行时 dlsym 仍可解析未加密的部分）。
//
// 所有输出走 __android_log_print（logcat tag: OpenWorldBox-Dump），
// 避免在 ImGui 菜单中显示大量文本。
//
// 用法（由 jni_bridge.cpp 转发调用）：
//   DumpEngine::dumpLoadedModules();
//   void* p = DumpEngine::resolveSymbol("_ZN6Player6attackEP6Entity");
//   DumpEngine::dumpMemoryAt(p, 64);
//   DumpEngine::dumpStringSearch("Player");

#pragma once

#include <cstdint>
#include <cstddef>

namespace owb {

class DumpEngine {
public:
    // ============ 模块枚举 ============

    /**
     * 枚举当前进程所有已加载 .so，输出到 logcat。
     * 重点标记 libminecraftpe.so 的基址/大小/路径。
     *
     * @return 加载的模块总数
     */
    static int dumpLoadedModules();

    /**
     * 获取指定模块的基址与大小。
     * 不输出日志，供其他 dump 函数使用。
     *
     * @param moduleName 模块名（如 "libminecraftpe.so"）
     * @param base [out] 基址
     * @param size [out] 大小
     * @return true 表示找到
     */
    static bool getModuleInfo(const char* moduleName,
                              void** base,
                              size_t* size);

    // ============ 符号解析 ============

    /**
     * 用 dlsym 解析指定符号。
     * 同时输出到 logcat（解析成功/失败都输出）。
     *
     * @param symbol 完整 mangled 符号名（如 "_ZN6Player6attackEP6Entity"）
     * @return 符号地址，失败返回 nullptr
     */
    static void* resolveSymbol(const char* symbol);

    /**
     * 批量解析符号列表。
     * 用于一次性验证多个候选符号。
     *
     * @param symbols 以 nullptr 结尾的符号名数组
     */
    static void resolveSymbolBatch(const char** symbols);

    // ============ 内存 dump ============

    /**
     * 以 hex 形式 dump 指定地址附近的内存。
     *
     * @param addr 起始地址（必须已映射可读）
     * @param size 字节数
     */
    static void dumpMemoryAt(const void* addr, size_t size);

    /**
     * Dump 指定模块指定偏移处的内存。
     *
     * @param moduleName 模块名
     * @param offset 模块内偏移
     * @param size 字节数
     */
    static void dumpModuleMemory(const char* moduleName,
                                 size_t offset,
                                 size_t size);

    // ============ 字符串扫描 ============

    /**
     * 在指定模块内存范围内扫描包含关键词的 ASCII 字符串。
     * 用于在 .so 中查找类名/方法名字符串引用，
     * 间接定位相关函数。
     *
     * @param moduleName 模块名
     * @param keyword 关键词（大小写敏感）
     * @param maxResults 最多输出多少个匹配
     * @return 实际找到的匹配数量
     */
    static int dumpStringSearch(const char* moduleName,
                                const char* keyword,
                                int maxResults = 20);

    // ============ 综合扫描 ============

    /**
     * 一次性执行完整的游戏模块扫描：
     *   1. 模块基址/大小
     *   2. 候选符号批量 dlsym
     *   3. 关键字符串扫描（Player / attack / Level / getEntities / LocalPlayer）
     *
     * 这是给 DumpModule 一键触发用的入口。
     */
    static void runFullScan();

private:
    // 判断指针是否可读（通过 /proc/self/maps）
    static bool isReadable(const void* addr, size_t size);

    // hex 格式化辅助
    static void logHex(const void* addr, size_t size);
};

} // namespace owb
