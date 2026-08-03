// OpenWorldBox Pattern Scanner
//
// 在模块内存范围内搜索字节模式（带通配符）。
// 用于在 libminecraftpe.so 被 strip 的情况下定位函数。
//
// 模式格式：形如 "AA BB ?? CC DD"（空格分隔，?? 表示通配）
// 用法：
//   void* addr = PatternScanner::find(
//       "libminecraftpe.so",
//       "AA BB ?? CC DD",
//       0  // 偏移
//   );

#pragma once

#include <cstdint>
#include <cstddef>
#include <vector>
#include "hook/hook_engine.h"

namespace owb {

class PatternScanner {
public:
    /**
     * 在模块内存中搜索字节模式
     *
     * @param moduleName 模块名（如 "libminecraftpe.so"）
     * @param pattern    模式字符串，如 "AA BB ?? CC"
     * @param offset     找到后的偏移量（用于跳过函数序言等）
     * @return 匹配地址，未找到返回 nullptr
     */
    static void* find(const char* moduleName, const char* pattern, int offset = 0);

    /**
     * 在指定内存范围内搜索
     *
     * @param start   起始地址
     * @param size    搜索长度
     * @param pattern 模式字符串
     * @param offset  偏移
     */
    static void* findIn(void* start, size_t size, const char* pattern, int offset = 0);

    /**
     * 解析模式字符串为字节 + 掩码数组
     */
    static bool parsePattern(const char* pattern,
                             std::vector<uint8_t>& bytes,
                             std::vector<bool>& mask);

private:
    static bool matchBytes(const uint8_t* target,
                           const std::vector<uint8_t>& bytes,
                           const std::vector<bool>& mask);
};

} // namespace owb
