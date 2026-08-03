// OpenWorldBox JNI 桥接 + ImGui 渲染主循环
//
// 集成 ImGui v1.90+，支持：
//   - 中文字体加载（从 /system/fonts/）
//   - 触摸事件 → ImGui IO 映射
//   - 反向回调 Kotlin 端绘制菜单（让 Kotlin 控制菜单结构）

#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <GLES3/gl3.h>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <vector>
#include <mutex>

#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"
#include "game/game_hooks.h"
#include "debug/dump_engine.h"

#define LOG_TAG "OpenWorldBox-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 外部声明：菜单绘制（在 imgui_menu.cpp）
namespace owb_menu {
    void initStyle();
    void renderMenu();
    // 即时绘制 API（供 Kotlin 反向调用）
    bool checkbox(const char* id, const char* label, bool current);
    float sliderFloat(const char* id, const char* label, float current, float min, float max);
    int sliderInt(const char* id, const char* label, int current, int min, int max);
    void text(const char* t);
    bool collapsingHeader(const char* label);
    bool beginTabBar(const char* id);
    void endTabBar();
    bool beginTabItem(const char* label);
    void endTabItem();
    void separator();
    void sameLine();
    bool button(const char* label);
    // 主窗口
    bool beginMainWindow(const char* title, float x, float y, float w, float h);
    void endMainWindow();
}

namespace {

std::mutex g_state_mutex;
bool g_initialized = false;
bool g_menu_visible = false;
int  g_width  = 0;
int  g_height = 0;

// 触摸状态（多点触控简化为单点）
float g_touch_x = -1.0f;
float g_touch_y = -1.0f;
bool  g_touch_down = false;

// JavaVM 用于反向回调 Kotlin
JavaVM* g_jvm = nullptr;

// ImGuiBridge 类的全局引用 + renderMenu 方法 ID
// renderMenu 是静态方法，所以这里保存 jclass 而非 jobject
jclass   g_imgui_bridge_cls = nullptr;
jmethodID g_render_menu_method = nullptr;

// ============ 字体加载 ============

// 从文件读取整个内容到 vector
std::vector<char> readFile(const char* path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return {};
    auto size = f.tellg();
    if (size <= 0) return {};
    f.seekg(0, std::ios::beg);
    std::vector<char> data(size);
    if (!f.read(data.data(), size)) return {};
    return data;
}

// 加载中文字体：尝试系统多个候选
// 返回的 vector 通过 ImFontConfig 持久使用
std::vector<char> g_font_data;  // 全局持有，避免释放

bool loadChineseFont() {
    // 候选字体（按优先级）
    const char* candidates[] = {
        "/system/fonts/DroidSansFallback.ttf",        // 老版本 Android
        "/system/fonts/NotoSansCJK-Regular.ttc",      // 较新 Android
        "/system/fonts/NotoSansSC-Regular.otf",       // 部分定制 ROM
        "/system/fonts/SourceHanSansSC-Regular.otf",  // 部分定制 ROM
        nullptr
    };

    for (int i = 0; candidates[i] != nullptr; i++) {
        g_font_data = readFile(candidates[i]);
        if (!g_font_data.empty()) {
            LOGI("已加载中文字体: %s (size=%zu)", candidates[i], g_font_data.size());

            ImGuiIO& io = ImGui::GetIO();
            ImFontConfig config;
            config.FontDataOwnedByAtlas = false;  // 我们自己管理内存

            // 加载字体，包含中文常用范围
            // glyph_ranges 用 ImFontGlyphRangesBuilder 动态构建更灵活
            const ImWchar* ranges = io.Fonts->GetGlyphRangesChineseFull();
            io.Fonts->AddFontFromMemoryTTF(
                g_font_data.data(),
                (int)g_font_data.size(),
                20.0f,
                &config,
                ranges
            );
            return true;
        }
    }
    LOGE("未找到任何中文字体，菜单将无法显示中文");
    return false;
}

// ============ ImGui 初始化 ============

void initImGui() {
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();

    // 加载字体
    loadChineseFont();

    // 风格
    owb_menu::initStyle();

    // 后端
    ImGui_ImplOpenGL3_Init("#version 300 es");

    // 配置
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableSetMousePos;
    io.BackendPlatformName = "OpenWorldBox-Android";

    LOGI("ImGui 初始化完成 (version: %s)", IMGUI_VERSION);
}

// ============ 反向回调 Kotlin ============

// 调用 ImGuiBridge.renderMenu()，让 Kotlin 控制菜单内容
void invokeKotlinRenderMenu() {
    if (!g_jvm || !g_imgui_bridge_cls || !g_render_menu_method) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            return;
        }
    }

    env->CallStaticVoidMethod(g_imgui_bridge_cls, g_render_menu_method);

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

} // namespace

// ============ JNI 实现 ============

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeInit(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lk(g_state_mutex);
    if (g_initialized) return;

    // 保存 ImGuiBridge 类的引用 + renderMenu 方法 ID，用于反向回调
    if (!g_imgui_bridge_cls) {
        jclass local = env->FindClass("com/openworldbox/ui/ImGuiBridge");
        if (local) {
            g_imgui_bridge_cls = (jclass)env->NewGlobalRef(local);
            g_render_menu_method = env->GetStaticMethodID(
                g_imgui_bridge_cls, "renderMenu", "()V");
            env->DeleteLocalRef(local);
            if (g_render_menu_method) {
                LOGI("已绑定 ImGuiBridge.renderMenu() 回调");
            } else {
                LOGE("未找到 ImGuiBridge.renderMenu() 方法");
            }
        } else {
            LOGE("未找到 ImGuiBridge 类");
        }
    }

    initImGui();
    g_initialized = true;

    // 安装游戏 hooks（解析函数地址 / 捕获关键对象指针）
    // 注意：游戏库可能尚未加载，install 内部会安全降级
    owb::GameHooks::install();

    LOGI("nativeInit 完成");
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSurfaceChanged(JNIEnv*, jclass, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_state_mutex);
    g_width = w;
    g_height = h;
    glViewport(0, 0, w, h);
    if (g_initialized) {
        ImGuiIO& io = ImGui::GetIO();
        io.DisplaySize = ImVec2((float)w, (float)h);
    }
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeRender(JNIEnv*, jclass) {
    if (!g_initialized) return;

    // 清屏为透明
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    ImGuiIO& io = ImGui::GetIO();

    // 新帧
    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();

    // 触发模块 tick（每帧一次）
    {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
        }
        if (env) {
            // 调用 NativeBridge.nativeTickModules() → ModuleManager.tick()
            // 注意：nativeTickModules 内部会 FindClass 调 ModuleManager.tick()
            // 这里直接复用同样逻辑
            static jmethodID tickMethod = nullptr;
            if (!tickMethod) {
                jclass cls = env->FindClass("com/openworldbox/module/ModuleManager");
                if (cls) {
                    tickMethod = env->GetStaticMethodID(cls, "tick", "()V");
                    env->DeleteLocalRef(cls);
                }
            }
            if (tickMethod) {
                jclass cls = env->FindClass("com/openworldbox/module/ModuleManager");
                if (cls) {
                    env->CallStaticVoidMethod(cls, tickMethod);
                    env->DeleteLocalRef(cls);
                }
            }
        }
        if (attached) g_jvm->DetachCurrentThread();
    }

    if (g_menu_visible) {
        // 反向回调 Kotlin 绘制菜单
        invokeKotlinRenderMenu();
    } else {
        // 菜单隐藏时画一个标识（提示用户按音量上键）
        ImGui::SetNextWindowPos(ImVec2(10, 10));
        ImGui::Begin("##hint", nullptr,
            ImGuiWindowFlags_NoTitleBar |
            ImGuiWindowFlags_NoResize |
            ImGuiWindowFlags_NoMove |
            ImGuiWindowFlags_AlwaysAutoResize |
            ImGuiWindowFlags_NoSavedSettings |
            ImGuiWindowFlags_NoFocusOnAppearing);
        ImGui::TextDisabled("OpenWorldBox - 按音量上键打开菜单");
        ImGui::End();
    }

    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeShutdown(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_state_mutex);
    if (!g_initialized) return;
    owb::GameHooks::uninstall();
    ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext();
    g_initialized = false;
    LOGI("nativeShutdown 完成");
}

// ============ 输入 ============

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeTouchEvent(JNIEnv*, jclass, jint action, jfloat x, jfloat y) {
    if (!g_initialized) return;
    std::lock_guard<std::mutex> lk(g_state_mutex);
    ImGuiIO& io = ImGui::GetIO();

    g_touch_x = x;
    g_touch_y = y;
    io.AddMousePosEvent(x, y);

    // action: 0=DOWN, 1=UP, 2=MOVE, 5=POINTER_DOWN, 6=POINTER_UP
    bool down = (action == 0 || action == 5 || action == 2);
    g_touch_down = down;
    io.AddMouseButtonEvent(0, down);
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeKeyEvent(JNIEnv*, jclass, jint action, jint keyCode, jint unicodeChar) {
    if (!g_initialized) return;
    ImGuiIO& io = ImGui::GetIO();

    bool down = (action == 0);  // ACTION_DOWN
    // 映射常见按键到 ImGui
    if (keyCode == 67 /* KEYCODE_DEL */) {
        io.AddKeyEvent(ImGuiKey_Backspace, down);
    } else if (keyCode == 66 /* KEYCODE_ENTER */) {
        io.AddKeyEvent(ImGuiKey_Enter, down);
    } else if (unicodeChar != 0 && down) {
        // 添加字符输入
        char buf[5] = {0};
        // 简化：只处理 ASCII
        if (unicodeChar < 128) {
            buf[0] = (char)unicodeChar;
            io.AddInputCharactersUTF8(buf);
        }
    }
}

// ============ 菜单状态 ============

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSetMenuVisible(JNIEnv*, jclass, jboolean visible) {
    std::lock_guard<std::mutex> lk(g_state_mutex);
    g_menu_visible = visible == JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeIsMenuVisible(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_state_mutex);
    return g_menu_visible ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeOnModuleStateChanged(JNIEnv* env, jclass, jstring moduleId, jboolean enabled) {
    const char* id = moduleId ? env->GetStringUTFChars(moduleId, nullptr) : nullptr;
    if (id) {
        LOGI("module state: %s = %s", id, enabled ? "on" : "off");
        env->ReleaseStringUTFChars(moduleId, id);
    }
}

// ============ 即时绘制（供 Kotlin 反向调用） ============

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeCheckbox(JNIEnv* env, jclass, jstring jid, jstring jlabel, jboolean current) {
    const char* id = env->GetStringUTFChars(jid, nullptr);
    const char* label = env->GetStringUTFChars(jlabel, nullptr);
    bool v = current == JNI_TRUE;
    bool newV = owb_menu::checkbox(id, label, v);
    env->ReleaseStringUTFChars(jid, id);
    env->ReleaseStringUTFChars(jlabel, label);
    return newV ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSliderFloat(JNIEnv* env, jclass, jstring jid, jstring jlabel, jfloat current, jfloat min, jfloat max) {
    const char* id = env->GetStringUTFChars(jid, nullptr);
    const char* label = env->GetStringUTFChars(jlabel, nullptr);
    float v = owb_menu::sliderFloat(id, label, current, min, max);
    env->ReleaseStringUTFChars(jid, id);
    env->ReleaseStringUTFChars(jlabel, label);
    return v;
}

JNIEXPORT jint JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSliderInt(JNIEnv* env, jclass, jstring jid, jstring jlabel, jint current, jint min, jint max) {
    const char* id = env->GetStringUTFChars(jid, nullptr);
    const char* label = env->GetStringUTFChars(jlabel, nullptr);
    int v = owb_menu::sliderInt(id, label, current, min, max);
    env->ReleaseStringUTFChars(jid, id);
    env->ReleaseStringUTFChars(jlabel, label);
    return v;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeText(JNIEnv* env, jclass, jstring jtext) {
    const char* t = env->GetStringUTFChars(jtext, nullptr);
    owb_menu::text(t);
    env->ReleaseStringUTFChars(jtext, t);
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeCollapsingHeader(JNIEnv* env, jclass, jstring jlabel) {
    const char* label = env->GetStringUTFChars(jlabel, nullptr);
    bool v = owb_menu::collapsingHeader(label);
    env->ReleaseStringUTFChars(jlabel, label);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeBeginTabBar(JNIEnv* env, jclass, jstring jid) {
    const char* id = env->GetStringUTFChars(jid, nullptr);
    bool v = owb_menu::beginTabBar(id);
    env->ReleaseStringUTFChars(jid, id);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeEndTabBar(JNIEnv*, jclass) {
    owb_menu::endTabBar();
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeBeginTabItem(JNIEnv* env, jclass, jstring jlabel) {
    const char* label = env->GetStringUTFChars(jlabel, nullptr);
    bool v = owb_menu::beginTabItem(label);
    env->ReleaseStringUTFChars(jlabel, label);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeEndTabItem(JNIEnv*, jclass) {
    owb_menu::endTabItem();
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSeparator(JNIEnv*, jclass) {
    owb_menu::separator();
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSameLine(JNIEnv*, jclass) {
    owb_menu::sameLine();
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeButton(JNIEnv* env, jclass, jstring jlabel) {
    const char* label = env->GetStringUTFChars(jlabel, nullptr);
    bool v = owb_menu::button(label);
    env->ReleaseStringUTFChars(jlabel, label);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeBeginMainWindow(JNIEnv* env, jclass, jstring jtitle, jfloat x, jfloat y, jfloat w, jfloat h) {
    const char* title = env->GetStringUTFChars(jtitle, nullptr);
    bool v = owb_menu::beginMainWindow(title, x, y, w, h);
    env->ReleaseStringUTFChars(jtitle, title);
    return v ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeEndMainWindow(JNIEnv*, jclass) {
    owb_menu::endMainWindow();
}

// ============ 游戏数据 API ============
//
// 转发到 owb::GameHooks 的对应实现。
// GameHooks 当前为"安全降级"实现：
//   - 偏移为 0 / 函数地址未解析时返回默认值，不崩溃
//   - 接入真实游戏（填入 game_offsets.h / GamePatterns）后自动工作

JNIEXPORT jfloatArray JNICALL
Java_com_openworldbox_core_NativeBridge_nativeGetLocalPlayerPos(JNIEnv* env, jclass) {
    jfloatArray arr = env->NewFloatArray(3);
    if (arr) {
        float pos[3] = {0.0f, 0.0f, 0.0f};
        owb::GameHooks::getLocalPlayerPos(pos);
        env->SetFloatArrayRegion(arr, 0, 3, pos);
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_openworldbox_core_NativeBridge_nativeGetLocalPlayerRotation(JNIEnv* env, jclass) {
    jfloatArray arr = env->NewFloatArray(2);
    if (arr) {
        float rot[2] = {0.0f, 0.0f};
        owb::GameHooks::getLocalPlayerRotation(rot);
        env->SetFloatArrayRegion(arr, 0, 2, rot);
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_openworldbox_core_NativeBridge_nativeQueryEntities(JNIEnv* env, jclass) {
    std::vector<float> data;
    int count = owb::GameHooks::queryEntities(data);
    jfloatArray arr = env->NewFloatArray((jsize)data.size());
    if (arr && count > 0) {
        env->SetFloatArrayRegion(arr, 0, (jsize)data.size(), data.data());
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeAttackEntity(JNIEnv* env, jclass, jlong entityId, jboolean swingHand) {
    bool ok = owb::GameHooks::attackEntity((int64_t)entityId, swingHand == JNI_TRUE);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeSetRotationSilent(JNIEnv*, jclass, jfloat yaw, jfloat pitch) {
    owb::GameHooks::setRotationSilent(yaw, pitch);
}

JNIEXPORT jboolean JNICALL
Java_com_openworldbox_core_NativeBridge_nativeIsLineOfSightClear(JNIEnv*, jclass, jdouble x, jdouble y, jdouble z) {
    bool clear = owb::GameHooks::isLineOfSightClear((double)x, (double)y, (double)z);
    return clear ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeTickModules(JNIEnv* env, jclass) {
    // 转发到 Kotlin: ModuleManager.tick()
    static jmethodID tickMethod = nullptr;
    if (!tickMethod) {
        jclass cls = env->FindClass("com/openworldbox/module/ModuleManager");
        if (cls) {
            tickMethod = env->GetStaticMethodID(cls, "tick", "()V");
            env->DeleteLocalRef(cls);
        }
    }
    if (tickMethod) {
        // 用 ImGuiBridge 同样的模式：通过 jclass 调静态方法
        // 这里简化：每次重新 FindClass（性能可接受）
        jclass cls = env->FindClass("com/openworldbox/module/ModuleManager");
        if (cls) {
            env->CallStaticVoidMethod(cls, tickMethod);
            env->DeleteLocalRef(cls);
        }
    }
}

// ============ 调试 Dump 实现 ============
//
// 转发到 owb::DumpEngine，用于在游戏进程内运行时分析
// libminecraftpe.so（被符号加密，静态分析拿不到符号）。
// 结果走 logcat（tag: OpenWorldBox-Dump）。

JNIEXPORT jint JNICALL
Java_com_openworldbox_core_NativeBridge_nativeDumpLoadedModules(JNIEnv*, jclass) {
    return (jint)owb::DumpEngine::dumpLoadedModules();
}

JNIEXPORT jstring JNICALL
Java_com_openworldbox_core_NativeBridge_nativeDumpResolveSymbol(JNIEnv* env, jclass, jstring jsymbol) {
    const char* sym = jsymbol ? env->GetStringUTFChars(jsymbol, nullptr) : nullptr;
    if (!sym) {
        return env->NewStringUTF("");
    }
    void* addr = owb::DumpEngine::resolveSymbol(sym);
    env->ReleaseStringUTFChars(jsymbol, sym);
    if (!addr) {
        return env->NewStringUTF("");
    }
    char buf[32];
    snprintf(buf, sizeof(buf), "0x%lx", (unsigned long)addr);
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeDumpModuleMemory(JNIEnv* env, jclass, jstring jmodule, jlong offset, jint size) {
    const char* mod = jmodule ? env->GetStringUTFChars(jmodule, nullptr) : nullptr;
    if (!mod) return;
    owb::DumpEngine::dumpModuleMemory(mod, (size_t)offset, (size_t)size);
    env->ReleaseStringUTFChars(jmodule, mod);
}

JNIEXPORT jint JNICALL
Java_com_openworldbox_core_NativeBridge_nativeDumpStringSearch(JNIEnv* env, jclass, jstring jmodule, jstring jkeyword, jint maxResults) {
    const char* mod = jmodule ? env->GetStringUTFChars(jmodule, nullptr) : nullptr;
    const char* kw = jkeyword ? env->GetStringUTFChars(jkeyword, nullptr) : nullptr;
    if (!mod || !kw) {
        if (mod) env->ReleaseStringUTFChars(jmodule, mod);
        if (kw)  env->ReleaseStringUTFChars(jkeyword, kw);
        return 0;
    }
    int found = owb::DumpEngine::dumpStringSearch(mod, kw, (int)maxResults);
    env->ReleaseStringUTFChars(jmodule, mod);
    env->ReleaseStringUTFChars(jkeyword, kw);
    return (jint)found;
}

JNIEXPORT void JNICALL
Java_com_openworldbox_core_NativeBridge_nativeDumpRunFullScan(JNIEnv*, jclass) {
    owb::DumpEngine::runFullScan();
}

} // extern "C"
