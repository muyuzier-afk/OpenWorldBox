package com.openworldbox.module.impl

import com.openworldbox.config.IntBean
import com.openworldbox.config.StringBean
import com.openworldbox.core.NativeBridge
import com.openworldbox.module.Category
import com.openworldbox.module.Module
import com.openworldbox.util.Logger

/**
 * 调试 Dump 模块
 *
 * 用于辅助逆向分析 libminecraftpe.so（被网易符号加密加固，
 * 静态分析拿不到符号）。本模块在游戏进程内运行时执行：
 *   - 枚举已加载 .so（找 libminecraftpe.so 基址）
 *   - dlsym 解析候选符号（验证哪些符号未 strip）
 *   - 内存 hex dump（按偏移查看 .so 内容）
 *   - 字符串扫描（找类名/方法名字符串引用）
 *
 * 所有结果走 logcat（tag: OpenWorldBox-Dump），不在 UI 显示。
 *
 * 菜单中应看到（调试 Tab）：
 *   Dump 工具
 *     选项:dump
 *       - 自定义符号 (文本，用于 dlsym)
 *       - 自定义关键词 (文本，用于字符串扫描)
 *       - 内存偏移 (滑块，用于 dumpModuleMemory)
 *       - dump 大小 (滑块，32~4096 字节)
 *
 * 该模块没有"启用/禁用"语义，所有操作通过菜单按钮触发
 * （由 ImGuiBridge 在检测到 DumpModule 时额外渲染按钮）。
 */
object DumpModule : Module(
    id = "dump",
    displayName = "Dump 工具",
    category = Category.DEBUG,
    description = "游戏内存调试 dump（输出走 logcat: OpenWorldBox-Dump）"
) {
    /** 默认扫描的游戏模块 */
    const val GAME_MODULE = "libminecraftpe.so"

    /** 自定义符号名（用于 nativeDumpResolveSymbol） */
    val customSymbol = option(StringBean(
        key = "custom_symbol",
        displayName = "自定义符号 (mangled)",
        defaultValue = "_ZN6Player6attackEP6Entity"
    ))

    /** 自定义扫描关键词（用于 nativeDumpStringSearch） */
    val customKeyword = option(StringBean(
        key = "custom_keyword",
        displayName = "字符串扫描关键词",
        defaultValue = "Player"
    ))

    /** 内存偏移（用于 nativeDumpModuleMemory） */
    val memOffset = option(IntBean(
        key = "mem_offset",
        displayName = "内存偏移",
        defaultValue = 0,
        min = 0,
        max = 0x40000000  // 1GB 上限（足够覆盖整个 .so）
    ))

    /** dump 字节数 */
    val memSize = option(IntBean(
        key = "mem_size",
        displayName = "dump 大小",
        defaultValue = 64,
        min = 16,
        max = 4096
    ))

    // ============ 按钮动作（由 ImGuiBridge 调用） ============

    /** 一键全量扫描：模块列表 + 候选符号 + 关键字符串 */
    fun runFullScan() {
        Logger.i("DumpModule: 触发全量扫描")
        try {
            NativeBridge.nativeDumpRunFullScan()
        } catch (t: Throwable) {
            Logger.e("DumpModule 全量扫描失败", t)
        }
    }

    /** 枚举已加载 .so */
    fun dumpModules() {
        try {
            val count = NativeBridge.nativeDumpLoadedModules()
            Logger.i("DumpModule: 已加载 $count 个模块")
        } catch (t: Throwable) {
            Logger.e("DumpModule 枚举模块失败", t)
        }
    }

    /** 用 dlsym 解析自定义符号 */
    fun resolveCustomSymbol() {
        val sym = customSymbol.get()
        if (sym.isBlank()) {
            Logger.w("DumpModule: 符号为空")
            return
        }
        try {
            val addr = NativeBridge.nativeDumpResolveSymbol(sym)
            if (addr.isBlank()) {
                Logger.i("DumpModule: ✗ $sym (未找到)")
            } else {
                Logger.i("DumpModule: ✓ $sym = $addr")
            }
        } catch (t: Throwable) {
            Logger.e("DumpModule dlsym 失败", t)
        }
    }

    /** 在 .so 中扫描自定义关键词 */
    fun searchCustomKeyword() {
        val kw = customKeyword.get()
        if (kw.isBlank()) {
            Logger.w("DumpModule: 关键词为空")
            return
        }
        try {
            val found = NativeBridge.nativeDumpStringSearch(GAME_MODULE, kw, 20)
            Logger.i("DumpModule: 扫描 \"$kw\" 找到 $found 个匹配（看 logcat）")
        } catch (t: Throwable) {
            Logger.e("DumpModule 字符串扫描失败", t)
        }
    }

    /** Dump 指定偏移处的内存 */
    fun dumpMemory() {
        try {
            NativeBridge.nativeDumpModuleMemory(GAME_MODULE, memOffset.get().toLong(), memSize.get())
            Logger.i("DumpModule: dump ${GAME_MODULE}+0x${memOffset.get().toString(16)} (${memSize.get()} 字节)")
        } catch (t: Throwable) {
            Logger.e("DumpModule 内存 dump 失败", t)
        }
    }

    /** 常用候选符号一次性 dlsym（内部清单） */
    fun dumpCommonSymbols() {
        // 这些是 MC 1.21.50 中 KillAura 最可能用到的符号
        val candidates = listOf(
            "_ZN6Player6attackEP6Entity",
            "_ZN6Player6attackER6Entity",
            "_ZN5Level12getEntitiesEv",
            "_ZNK5Level12getEntitiesEv",
            "_ZN9Minecraft14getLocalPlayerEv",
            "_ZNK9Minecraft14getLocalPlayerEv",
            "_ZNK5Actor7getPosEv",
            "_ZNK5Actor13getRotationEv",
            "_ZNK3Mob5getHealthEv",
            "_ZNK3Mob12getMaxHealthEv",
            "_ZN5Level14hasLineOfSightERK5ActorS2_",
            "_ZNK5Actor14hasLineOfSightERKS_"
        )
        var ok = 0
        for (sym in candidates) {
            try {
                val addr = NativeBridge.nativeDumpResolveSymbol(sym)
                if (addr.isNotBlank()) {
                    Logger.i("DumpModule: ✓ $sym = $addr")
                    ok++
                } else {
                    Logger.i("DumpModule: ✗ $sym")
                }
            } catch (_: Throwable) {}
        }
        Logger.i("DumpModule: 候选符号 $ok/${candidates.size} 解析成功")
    }
}
