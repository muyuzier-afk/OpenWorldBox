# OpenWorldBox native 构建脚本
cmake_minimum_required(VERSION 3.22.1)
project(openworldbox LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============ ImGui ============
set(IMGUI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/imgui)
if(NOT EXISTS ${IMGUI_DIR}/imgui.h)
    message(FATAL_ERROR
        "未找到 ImGui 源码。请执行：\n"
        "  git submodule update --init --recursive")
endif()

# ============ Dobby (inline hook 库) ============
set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/dobby)
if(NOT EXISTS ${DOBBY_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "未找到 Dobby 源码。请执行：\n"
        "  git submodule update --init --recursive")
endif()

# Dobby 配置：禁用测试和示例，减小体积
set(DOBBY_BUILD_TEST_LIBRARY OFF CACHE BOOL "" FORCE)
set(DOBBY_BUILD_STATIC_LIBRARY ON CACHE BOOL "" FORCE)
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(NearBranchTrampoline ON CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)

# ============ Patch Dobby asm 兼容 NDK 25+ ============
# NDK 25 的 clang 对 ADRP 引用的符号要求显式 .extern 声明，
# 否则报 "invalid symbol kind for ADRP relocation"。
# Dobby 的 closure_bridge_arm64.asm 引用了 C 文件定义的
# common_closure_bridge_handler 但未声明 .extern，这里自动补上。
# 幂等：只在文件未包含 .extern 声明时才 patch。
set(DOBBY_ASM_FILE "${DOBBY_DIR}/source/TrampolineBridge/ClosureTrampolineBridge/arm64/closure_bridge_arm64.asm")
if(EXISTS ${DOBBY_ASM_FILE})
    file(READ ${DOBBY_ASM_FILE} DOBBY_ASM_CONTENT)
    if(NOT DOBBY_ASM_CONTENT MATCHES "\\.extern.*common_closure_bridge_handler")
        string(REPLACE
            "adrp TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGE"
            ".extern cdecl(common_closure_bridge_handler)\nadrp TMP_REG_0, cdecl(common_closure_bridge_handler)@PAGE"
            DOBBY_ASM_PATCHED "${DOBBY_ASM_CONTENT}")
        file(WRITE ${DOBBY_ASM_FILE} "${DOBBY_ASM_PATCHED}")
        message(STATUS "Patched Dobby closure_bridge_arm64.asm for NDK 25+ compatibility")
    endif()
endif()

# 通过 add_subdirectory 集成 Dobby（生成静态库 libdobby）
add_subdirectory(${DOBBY_DIR} ${CMAKE_BINARY_DIR}/dobby)

# ============ 源文件 ============
set(NATIVE_SOURCES
    jni_bridge.cpp
    imgui_menu.cpp
    hook/hook_engine.cpp
    hook/pattern_scan.cpp
    game/game_hooks.cpp
    game/actor_access.cpp
    debug/dump_engine.cpp
    # ImGui 核心
    ${IMGUI_DIR}/imgui.cpp
    ${IMGUI_DIR}/imgui_draw.cpp
    ${IMGUI_DIR}/imgui_tables.cpp
    ${IMGUI_DIR}/imgui_widgets.cpp
    ${IMGUI_DIR}/imgui_demo.cpp
    # OpenGL3 后端
    ${IMGUI_DIR}/backends/imgui_impl_opengl3.cpp
)

add_library(${PROJECT_NAME} SHARED ${NATIVE_SOURCES})

# ============ 链接库 ============
find_library(log-lib log)
find_library(gles-lib GLESv3)
find_library(egl-lib EGL)
find_library(android-lib android)

target_link_libraries(${PROJECT_NAME}
    ${log-lib}
    ${gles-lib}
    ${egl-lib}
    ${android-lib}
    dobby  # Dobby 静态库
)

# ============ 头文件包含 ============
target_compile_definitions(${PROJECT_NAME} PRIVATE
    OWB_WITH_IMGUI=1
    OWB_WITH_HOOKS=1
)
target_include_directories(${PROJECT_NAME} PRIVATE
    ${IMGUI_DIR}
    ${IMGUI_DIR}/backends
    ${DOBBY_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Release 优化
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(${PROJECT_NAME} PRIVATE -O2 -fvisibility=hidden)
endif()
