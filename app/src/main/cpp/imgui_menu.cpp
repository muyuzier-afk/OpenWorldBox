// OpenWorldBox ImGui 菜单绘制实现
//
// 这里只提供"立即模式"封装函数，实际菜单结构由 Kotlin 端通过
// ImGuiBridge.renderMenu() 反向调用来构建——Kotlin 遍历 ModuleManager，
// 调用 NativeBridge.nativeCheckbox / nativeSliderFloat 等绘制具体内容。

#include "imgui.h"
#include <cstring>

namespace owb_menu {

// ============ 风格初始化 ============

void initStyle() {
    ImGuiStyle& style = ImGui::GetStyle();

    // 整体圆润现代风
    style.WindowRounding    = 8.0f;
    style.ChildRounding     = 6.0f;
    style.FrameRounding     = 4.0f;
    style.PopupRounding     = 4.0f;
    style.ScrollbarRounding = 9.0f;
    style.GrabRounding      = 4.0f;
    style.TabRounding       = 4.0f;

    style.WindowPadding    = ImVec2(12, 12);
    style.FramePadding     = ImVec2(8, 4);
    style.ItemSpacing      = ImVec2(8, 6);
    style.ItemInnerSpacing = ImVec2(6, 4);
    style.WindowBorderSize = 1.0f;
    style.FrameBorderSize  = 0.0f;

    // 配色（深色 + 蓝色强调）
    ImVec4* c = style.Colors;
    c[ImGuiCol_WindowBg]           = ImVec4(0.10f, 0.10f, 0.12f, 0.92f);
    c[ImGuiCol_ChildBg]            = ImVec4(0.14f, 0.14f, 0.16f, 1.00f);
    c[ImGuiCol_PopupBg]            = ImVec4(0.12f, 0.12f, 0.14f, 0.96f);
    c[ImGuiCol_Border]             = ImVec4(0.25f, 0.25f, 0.28f, 0.60f);
    c[ImGuiCol_Text]               = ImVec4(0.92f, 0.92f, 0.94f, 1.00f);
    c[ImGuiCol_TextDisabled]       = ImVec4(0.50f, 0.50f, 0.55f, 1.00f);

    c[ImGuiCol_FrameBg]            = ImVec4(0.20f, 0.22f, 0.27f, 1.00f);
    c[ImGuiCol_FrameBgHovered]     = ImVec4(0.28f, 0.30f, 0.36f, 1.00f);
    c[ImGuiCol_FrameBgActive]      = ImVec4(0.36f, 0.40f, 0.48f, 1.00f);

    c[ImGuiCol_TitleBg]            = ImVec4(0.16f, 0.16f, 0.18f, 1.00f);
    c[ImGuiCol_TitleBgActive]      = ImVec4(0.22f, 0.36f, 0.62f, 1.00f);
    c[ImGuiCol_TitleBgCollapsed]   = ImVec4(0.10f, 0.10f, 0.12f, 1.00f);

    c[ImGuiCol_Button]             = ImVec4(0.22f, 0.36f, 0.62f, 1.00f);
    c[ImGuiCol_ButtonHovered]      = ImVec4(0.30f, 0.46f, 0.74f, 1.00f);
    c[ImGuiCol_ButtonActive]       = ImVec4(0.18f, 0.28f, 0.50f, 1.00f);

    c[ImGuiCol_Header]             = ImVec4(0.22f, 0.36f, 0.62f, 1.00f);
    c[ImGuiCol_HeaderHovered]      = ImVec4(0.30f, 0.46f, 0.74f, 1.00f);
    c[ImGuiCol_HeaderActive]       = ImVec4(0.18f, 0.28f, 0.50f, 1.00f);

    c[ImGuiCol_CheckMark]          = ImVec4(0.45f, 0.85f, 0.55f, 1.00f);

    c[ImGuiCol_SliderGrab]         = ImVec4(0.45f, 0.65f, 0.95f, 1.00f);
    c[ImGuiCol_SliderGrabActive]   = ImVec4(0.30f, 0.50f, 0.85f, 1.00f);

    c[ImGuiCol_ScrollbarBg]        = ImVec4(0.10f, 0.10f, 0.12f, 0.60f);
    c[ImGuiCol_ScrollbarGrab]      = ImVec4(0.30f, 0.30f, 0.34f, 1.00f);
    c[ImGuiCol_ScrollbarGrabHovered] = ImVec4(0.40f, 0.40f, 0.45f, 1.00f);
    c[ImGuiCol_ScrollbarGrabActive] = ImVec4(0.50f, 0.50f, 0.55f, 1.00f);

    c[ImGuiCol_Tab]                = ImVec4(0.16f, 0.16f, 0.18f, 1.00f);
    c[ImGuiCol_TabHovered]         = ImVec4(0.30f, 0.46f, 0.74f, 1.00f);
    c[ImGuiCol_TabActive]          = ImVec4(0.22f, 0.36f, 0.62f, 1.00f);
}

// ============ 即时绘制 API ============

bool checkbox(const char* id, const char* label, bool current) {
    bool v = current;
    // 使用 PushID 保证唯一性
    ImGui::PushID(id);
    ImGui::Checkbox(label, &v);
    ImGui::PopID();
    return v;
}

float sliderFloat(const char* id, const char* label, float current, float min, float max) {
    float v = current;
    ImGui::PushID(id);
    ImGui::SliderFloat(label, &v, min, max, "%.2f");
    ImGui::PopID();
    return v;
}

int sliderInt(const char* id, const char* label, int current, int min, int max) {
    int v = current;
    ImGui::PushID(id);
    ImGui::SliderInt(label, &v, min, max);
    ImGui::PopID();
    return v;
}

void text(const char* t) {
    ImGui::TextUnformatted(t);
}

bool collapsingHeader(const char* label) {
    return ImGui::CollapsingHeader(label);
}

bool beginTabBar(const char* id) {
    return ImGui::BeginTabBar(id);
}

void endTabBar() {
    ImGui::EndTabBar();
}

bool beginTabItem(const char* label) {
    return ImGui::BeginTabItem(label);
}

void endTabItem() {
    ImGui::EndTabItem();
}

void separator() {
    ImGui::Separator();
}

void sameLine() {
    ImGui::SameLine();
}

bool button(const char* label) {
    return ImGui::Button(label);
}

// ============ 主窗口 ============

bool beginMainWindow(const char* title, float x, float y, float w, float h) {
    ImGui::SetNextWindowPos(ImVec2(x, y), ImGuiCond_FirstUseEver);
    ImGui::SetNextWindowSize(ImVec2(w, h), ImGuiCond_FirstUseEver);

    // 窗口关闭按钮处理：用 ImGuiWindowFlags_NoCollapse 但保留 close
    // 这里使用 NoCollapse 让窗口看起来更简洁
    return ImGui::Begin(title, nullptr,
        ImGuiWindowFlags_NoCollapse |
        ImGuiWindowFlags_NoScrollbar);
}

void endMainWindow() {
    ImGui::End();
}

} // namespace owb_menu
