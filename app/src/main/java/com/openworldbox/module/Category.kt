package com.openworldbox.module

/**
 * 模块分类
 *
 * 用于在菜单中对模块分组展示。
 * 顺序即菜单中的显示顺序。
 */
enum class Category(val displayName: String) {
    RENDER("视觉"),
    COMBAT("战斗"),
    PLAYER("玩家"),
    WORLD("世界"),
    MOVEMENT("移动"),
    MISC("其他"),
    DEBUG("调试");

    companion object {
        fun fromIndex(index: Int): Category =
            entries.getOrElse(index) { MISC }
    }
}
