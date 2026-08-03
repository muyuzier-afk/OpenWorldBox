package com.openworldbox.ui

import com.openworldbox.config.Bean
import com.openworldbox.config.BoolBean
import com.openworldbox.config.ColorBean
import com.openworldbox.config.EnumBean
import com.openworldbox.config.FloatBean
import com.openworldbox.config.IntBean
import com.openworldbox.config.KeyBean
import com.openworldbox.config.StringBean
import com.openworldbox.core.NativeBridge
import com.openworldbox.module.ModuleManager
import com.openworldbox.module.impl.DumpModule
import com.openworldbox.module.impl.KillAuraModule

/**
 * ImGui ↔ Kotlin 桥
 *
 * 关键方法：[renderMenu]
 * 由 native 端在每帧 ImGui::NewFrame() 之后通过 JNI 反向调用，
 * 由 Kotlin 决定菜单结构（遍历 [ModuleManager]），并通过
 * [NativeBridge] 的"即时绘制"方法（nativeCheckbox / nativeSliderFloat 等）
 * 实际绘制到 ImGui 上。
 *
 * 这种"立即模式 + 反向回调"模式的优势：
 *   - 菜单内容由 Kotlin 控制，无需在 native 维护模块镜像
 *   - 新增模块只需 Kotlin 端注册，菜单自动出现
 *   - 模块状态读写直接在 Kotlin 完成，原生层只负责绘制
 *
 * 注意：renderMenu 在 GL 线程被 native 通过 AttachCurrentThread 调用，
 * 因此不要在此处做主线程耗时操作；模块开关同步通过 NativeBridge 即可。
 */
object ImGuiBridge {

    /**
     * 由 native 反向调用，绘制主菜单。
     */
    @JvmStatic
    fun renderMenu() {
        // 主窗口
        // ImGui::Begin 在 native 端封装为直接调用比较麻烦，
        // 这里采用最简单的方式：用一个固定大小 + 可拖拽的窗口。
        if (!NativeBridge.nativeBeginMainWindow(
                "OpenWorldBox",
                40f, 80f,  // x, y
                480f, 600f // w, h
            )
        ) {
            // 窗口被关闭，等同于隐藏菜单
            return
        }

        // 标题
        NativeBridge.nativeText("OpenWorldBox v0.1.0")
        NativeBridge.nativeSeparator()

        // 模块列表（按分类作为 Tab）
        if (NativeBridge.nativeBeginTabBar("modules") != 0) {
            for ((category, modules) in ModuleManager.grouped()) {
                if (modules.isEmpty()) continue
                if (NativeBridge.nativeBeginTabItem(category.displayName)) {
                    renderModuleList(modules)
                    NativeBridge.nativeEndTabItem()
                }
            }
            NativeBridge.nativeEndTabBar()
        }

        NativeBridge.nativeSeparator()
        NativeBridge.nativeText("音量上键 切换菜单显隐")

        // KillAura 状态显示
        if (KillAuraModule.enabled) {
            val target = KillAuraModule.currentTarget
            if (target != null) {
                NativeBridge.nativeSeparator()
                NativeBridge.nativeText("KillAura 目标: ${target.name}")
                NativeBridge.nativeText("  位置: (${target.x.toInt()}, ${target.y.toInt()}, ${target.z.toInt()})")
                NativeBridge.nativeText("  血量: ${target.health.toInt()}/${target.maxHealth.toInt()}")
            }
        }

        NativeBridge.nativeEndMainWindow()
    }

    /**
     * 渲染一组模块及其选项。
     */
    private fun renderModuleList(modules: List<com.openworldbox.module.Module>) {
        for (m in modules) {
            // 模块开关
            val newEnabled = NativeBridge.nativeCheckbox("mod_${m.id}", m.displayName, m.enabled)
            if (newEnabled != m.enabled) {
                m.setEnabled(newEnabled)
            }

            // 展开模块选项
            if (m.options.isNotEmpty() && NativeBridge.nativeCollapsingHeader("选项:${m.id}")) {
                renderBeanList(m.id, m.options)

                // DumpModule 额外渲染按钮区
                if (m is DumpModule) {
                    renderDumpButtons()
                }
            }
        }
    }

    /**
     * DumpModule 专用按钮区。
     * 每个按钮触发一次 dump 操作，结果走 logcat。
     */
    private fun renderDumpButtons() {
        NativeBridge.nativeSeparator()
        NativeBridge.nativeText("== Dump 操作（结果看 logcat: OpenWorldBox-Dump）==")

        if (NativeBridge.nativeButton("全量扫描##dump_full")) {
            DumpModule.runFullScan()
        }
        NativeBridge.nativeSameLine()
        if (NativeBridge.nativeButton("枚举模块##dump_mods")) {
            DumpModule.dumpModules()
        }

        if (NativeBridge.nativeButton("常用符号 dlsym##dump_sym")) {
            DumpModule.dumpCommonSymbols()
        }
        NativeBridge.nativeSameLine()
        if (NativeBridge.nativeButton("解析自定义符号##dump_sym2")) {
            DumpModule.resolveCustomSymbol()
        }

        if (NativeBridge.nativeButton("扫描自定义关键词##dump_kw")) {
            DumpModule.searchCustomKeyword()
        }
        NativeBridge.nativeSameLine()
        if (NativeBridge.nativeButton("Dump 内存##dump_mem")) {
            DumpModule.dumpMemory()
        }
    }

    /**
     * 渲染 Bean 列表。
     */
    private fun renderBeanList(moduleId: String, beans: List<Bean<*>>) {
        for (bean in beans) {
            val beanId = "${moduleId}_${bean.key}"
            when (bean) {
                is BoolBean -> {
                    val v = NativeBridge.nativeCheckbox(beanId, bean.displayName, bean.value)
                    if (v != bean.value) bean.value = v
                }
                is IntBean -> {
                    val v = NativeBridge.nativeSliderInt(beanId, bean.displayName, bean.value, bean.min, bean.max)
                    if (v != bean.value) bean.value = v
                }
                is FloatBean -> {
                    val v = NativeBridge.nativeSliderFloat(beanId, bean.displayName, bean.value, bean.min, bean.max)
                    if (v != bean.value) bean.value = v
                }
                is EnumBean -> {
                    // 简化：用文字显示当前选项 + Prev/Next 按钮
                    NativeBridge.nativeText("${bean.displayName}: ${bean.selectedOption}")
                    NativeBridge.nativeSameLine()
                    if (NativeBridge.nativeButton("<##${beanId}_prev")) {
                        bean.value = (bean.value - 1).coerceAtLeast(0)
                    }
                    NativeBridge.nativeSameLine()
                    if (NativeBridge.nativeButton(">##${beanId}_next")) {
                        bean.value = (bean.value + 1).coerceAtMost(bean.options.size - 1)
                    }
                }
                is ColorBean -> {
                    NativeBridge.nativeText("${bean.displayName}: #${"%08X".format(bean.value)}")
                }
                is KeyBean -> {
                    NativeBridge.nativeText("${bean.displayName}: ${bean.value}")
                }
                is StringBean -> {
                    NativeBridge.nativeText("${bean.displayName}: ${bean.value}")
                }
            }
        }
    }

    /** 切换菜单显隐 */
    fun toggleMenu() {
        val overlay = currentOverlay() ?: return
        overlay.toggleMenu()
    }

    private fun currentOverlay(): RenderOverlay? {
        return GameActivityOverlayHolder.overlay
    }
}
