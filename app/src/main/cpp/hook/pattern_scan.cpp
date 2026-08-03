// OpenWorldBox Pattern Scanner 实现

#include "pattern_scan.h"
#include <android/log.h>
#include <cstring>
#include <cctype>

#define LOG_TAG "OpenWorldBox-Pattern"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace owb {

bool PatternScanner::parsePattern(const char* pattern,
                                  std::vector<uint8_t>& bytes,
                                  std::vector<bool>& mask) {
    bytes.clear();
    mask.clear();
    if (!pattern) return false;

    const char* p = pattern;
    while (*p) {
        // 跳过空格
        while (*p == ' ' || *p == '\t') p++;
        if (!*p) break;

        if (p[0] == '?' && p[1] == '?') {
            // 通配字节
            bytes.push_back(0);
            mask.push_back(false);
            p += 2;
        } else if (std::isxdigit((unsigned char)p[0]) && std::isxdigit((unsigned char)p[1])) {
            // 十六进制字节
            auto hexToNibble = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                return -1;
            };
            int hi = hexToNibble(p[0]);
            int lo = hexToNibble(p[1]);
            if (hi < 0 || lo < 0) {
                LOGE("模式解析失败: 非法字符 '%c%c'", p[0], p[1]);
                return false;
            }
            bytes.push_back((uint8_t)((hi << 4) | lo));
            mask.push_back(true);
            p += 2;
        } else {
            LOGE("模式解析失败: 非法字符 '%c' (位置 %ld)", *p, (long)(p - pattern));
            return false;
        }
    }

    if (bytes.empty()) {
        LOGE("模式为空");
        return false;
    }
    if (bytes.size() != mask.size()) {
        LOGE("模式/掩码长度不一致");
        return false;
    }
    return true;
}

bool PatternScanner::matchBytes(const uint8_t* target,
                                const std::vector<uint8_t>& bytes,
                                const std::vector<bool>& mask) {
    for (size_t i = 0; i < bytes.size(); i++) {
        if (mask[i] && target[i] != bytes[i]) return false;
    }
    return true;
}

void* PatternScanner::findIn(void* start, size_t size,
                             const char* pattern, int offset) {
    std::vector<uint8_t> bytes;
    std::vector<bool> mask;
    if (!parsePattern(pattern, bytes, mask)) return nullptr;

    if (size < bytes.size()) return nullptr;

    auto* p = (uint8_t*)start;
    size_t end = size - bytes.size();
    for (size_t i = 0; i <= end; i++) {
        if (matchBytes(p + i, bytes, mask)) {
            return p + i + offset;
        }
    }
    return nullptr;
}

void* PatternScanner::find(const char* moduleName,
                           const char* pattern, int offset) {
    void* base = nullptr;
    size_t size = 0;
    if (!HookEngine::getModuleRange(moduleName, &base, &size)) {
        LOGE("无法获取模块 %s 范围", moduleName);
        return nullptr;
    }

    void* result = findIn(base, size, pattern, offset);
    if (result) {
        LOGI("在 %s 中找到模式 %s: addr=%p (offset=%d)",
             moduleName, pattern, result, offset);
    } else {
        LOGI("在 %s 中未找到模式 %s", moduleName, pattern);
    }
    return result;
}

} // namespace owb
