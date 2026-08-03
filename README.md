# OpenWorldBox

网易我的世界 (com.netease.x19) 的 Xposed Mod 工具。

> **声明**：本项目定位为 Minecraft 模组开发框架，用于学习 Xposed 注入、ImGui 渲染、ModSDK 调用等技术。
> 请勿用于违反 Minecraft EULA 或网易用户协议的场景。

## 当前状态 (v0.3.0)

骨架已完成，包含以下能力：

- ✅ Xposed 模块入口 + 作用域声明 (com.netease.x19)
- ✅ Hook 游戏 MainActivity，注入到游戏进程
- ✅ 从游戏进程加载模块自身的 native 库
- ✅ WindowManager + GLSurfaceView 渲染 overlay
- ✅ **ImGui v1.90.4 集成（submodule）**
- ✅ **中文字体自动加载（从 /system/fonts/）**
- ✅ **触摸事件 → ImGui IO 完整映射**
- ✅ **完整菜单 UI（Tab 分类 + 模块开关 + 滑块/按钮）**
- ✅ 模块系统（Module / Category / ModuleManager / Bean）
- ✅ 示例模块（ExampleModule）
- ✅ 音量上键切换菜单显隐
- ✅ GitHub Actions 自动编译
- ✅ **配置持久化（SharedPreferences + JSON）**
- ✅ **KillAura 模块（首个真实功能）**
- ✅ **目标选择器（玩家/怪物/动物 + 距离/角度/血量/护甲 优先级）**
- ✅ **每帧 tick 调度（native → Kotlin 反向调用）**
- ✅ **菜单内显示当前攻击目标**
- ✅ **Inline Hook 框架（Dobby submodule + HookEngine 封装）**
- ✅ **Pattern Scan（兜底定位 strip 后的函数）**
- ✅ **GameHooks 安全降级实现（偏移未填时不崩溃）**
- ✅ **JNI 桥接全部接入 GameHooks（不再有 stub）**
- ✅ **DumpModule 调试模块（运行时 dump .so 信息，辅助逆向）**

## 项目结构

```
OpenWorldBox/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat                 # Gradle Wrapper
├── .github/workflows/build.yml           # CI 自动编译
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/xposed_init              ← Xposed 入口声明
        ├── res/values/{arrays,strings}.xml
        ├── java/com/openworldbox/
        │   ├── MainActivity.kt             ← 桌面图标占位
        │   ├── core/
        │   │   ├── HookInit.kt             ← Xposed 入口（hook 游戏）
        │   │   ├── GameActivity.kt         ← 注入后启动器
        │   │   └── NativeBridge.kt         ← JNI 声明（含即时绘制 API）
        │   ├── module/
        │   │   ├── Category.kt
        │   │   ├── Module.kt               ← 含持久化 + tick 钩子
        │   │   ├── ModuleManager.kt        ← 含配置加载/保存
        │   │   └── impl/
        │   │       ├── ExampleModule.kt
        │   │       └── KillAuraModule.kt   ← KillAura 实现
        │   ├── config/
        │   │   ├── Bean.kt                 ← 配置项基类 + 各种 Bean
        │   │   └── ConfigStore.kt          ← SharedPreferences 持久化
        │   ├── game/
        │   │   ├── GameEntity.kt           ← 实体数据结构
        │   │   └── TargetSelector.kt       ← 目标过滤+排序
        │   ├── ui/
        │   │   ├── RenderOverlay.kt        ← GLSurfaceView 容器
        │   │   └── ImGuiBridge.kt          ← 菜单绘制（Kotlin 侧）
        │   └── util/Logger.kt
        └── cpp/
            ├── CMakeLists.txt              ← 自动检测 ImGui + Dobby 源码
            ├── jni_bridge.cpp              ← JNI + ImGui 主循环 + GameHooks 接入
            ├── imgui_menu.cpp              ← 即时绘制 API 实现
            ├── imgui/                      ← ImGui submodule (v1.90.4)
            ├── dobby/                      ← Dobby submodule (inline hook)
            ├── hook/
            │   ├── hook_engine.{h,cpp}     ← Dobby 封装 + 模块符号解析
            │   └── pattern_scan.{h,cpp}    ← 字节模式扫描（strip 兜底）
            ├── game/
            │   ├── game_offsets.h          ← 偏移表（占位，待填）
            │   ├── actor_access.{h,cpp}    ← Actor 内存读取原语
            │   └── game_hooks.{h,cpp}      ← 安装 hook + NativeBridge 实现入口
            └── debug/
                └── dump_engine.{h,cpp}     ← 运行时 dump（dlsym/内存/字符串扫描）
```

## 构建

### 前置要求

- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- Android NDK 25+
- CMake 3.22.1+

### 本地构建

```bash
# 1. 克隆（含 submodule）
git clone --recursive <your-repo-url> OpenWorldBox
cd OpenWorldBox

# 如果已经克隆过，单独拉 submodule：
git submodule update --init --recursive

# 2. 构建
chmod +x gradlew
./gradlew assembleDebug        # Debug APK
./gradlew assembleRelease      # Release APK

# 3. APK 输出位置
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release-unsigned.apk
```

### CI 自动编译（GitHub Actions）

仓库根目录的 [.github/workflows/build.yml](.github/workflows/build.yml) 定义了 CI 流程：

**触发条件**：
- 推送到 `main` 分支（仅 `app/`、`build.gradle.kts` 等关键文件变更）
- PR 到 `main` 分支
- 发布 Release 时
- 手动 workflow_dispatch

**CI 流程**：
1. 拉取代码 + submodule（ImGui）
2. 安装 JDK 17 + Android SDK + NDK 25 + CMake 3.22.1
3. 执行 `assembleDebug` + `assembleRelease`
4. 上传 APK 为 artifact（保留 30~90 天）
5. 如果是 Release 触发，自动附加 APK 到 GitHub Release

下载 APK：
- Push/PR 触发：在 Actions 运行详情页底部的 "Artifacts" 区域下载
- Release 触发：在 Releases 页面直接下载

## 使用

1. 安装 APK 到设备
2. 在 LSPosed 中启用模块，勾选作用域 `com.netease.x19`
3. 强制停止游戏后重新启动
4. 进入游戏后，**按音量上键**打开菜单
5. 菜单顶部显示版本号，下方按分类 Tab 展示所有模块
6. 勾选/取消勾选模块开关，展开"选项"可调节参数

## 设计要点

### 反向回调的菜单绘制

传统做法是把模块数据序列化传给 native，native 端遍历绘制。本项目采用更灵活的方式：

```
nativeRender()
  └── ImGui::NewFrame()
  └── invokeKotlinRenderMenu()        ← JNI 反向调用
        └── ImGuiBridge.renderMenu()  ← Kotlin
              └── 遍历 ModuleManager.grouped()
                    └── NativeBridge.nativeCheckbox(...)  ← 回到 native
                          └── ImGui::Checkbox(...)
```

优势：
- 菜单结构由 Kotlin 控制，新增模块自动出现在菜单中
- 模块状态读写直接在 Kotlin，无同步开销
- native 只负责即时绘制，无需维护数据镜像

### 中文字体

从 `/system/fonts/` 加载，按优先级尝试：
1. `DroidSansFallback.ttf`（老版本 Android）
2. `NotoSansCJK-Regular.ttc`（较新 Android）
3. `NotoSansSC-Regular.otf` / `SourceHanSansSC-Regular.otf`（定制 ROM）

使用 `GetGlyphRangesChineseFull()` 包含完整中文字符。

### 输入映射

| Android 事件 | ImGui IO |
|---|---|
| `MotionEvent.ACTION_DOWN/MOVE/UP` | `AddMousePosEvent` + `AddMouseButtonEvent(0, ...)` |
| `KeyEvent.KEYCODE_DEL` | `ImGuiKey_Backspace` |
| `KeyEvent.KEYCODE_ENTER` | `ImGuiKey_Enter` |
| Unicode 字符 | `AddInputCharactersUTF8` |

### 配置持久化

- 存储位置：`/data/data/com.netease.x19/shared_prefs/openworldbox_config.xml`
- 格式：单一 key (`config_json`) 存储 JSON
- 加载时机：模块注册完成后立即应用快照
- 保存时机：模块开关变化、Bean 值变化时自动调用 `ModuleManager.persist()`

JSON 结构：
```json
{
  "modules": {
    "killaura": {
      "enabled": true,
      "options": {
        "range": 4.5, "cps": 8, "rotation_mode": 1,
        "attack_players": true, "attack_hostile": true,
        "attack_passive": false, "through_walls": false,
        "priority": 0, "max_angle": 90.0, "name_blacklist": ""
      }
    }
  }
}
```

### KillAura 模块

首个真实功能模块，参考原版"世界盒子工具"的字段设计。

**菜单参数（战斗 Tab → KillAura → 选项）**：

| 参数 | 类型 | 默认值 | 范围 | 说明 |
|---|---|---|---|---|
| 攻击距离 | Float | 4.5 | 2~8 | 方块距离 |
| CPS | Int | 8 | 1~20 | 每秒攻击次数 |
| 视角模式 | Enum | 静默 | 关闭/静默/锁定 | 0=不转动, 1=只发包, 2=实际转动 |
| 攻击玩家 | Bool | true | - | 是否攻击其他玩家 |
| 攻击怪物 | Bool | true | - | 是否攻击敌对生物 |
| 攻击动物 | Bool | false | - | 是否攻击友好生物 |
| 穿墙攻击 | Bool | false | - | 是否允许穿墙 |
| 目标优先级 | Enum | 距离 | 距离/角度/血量/护甲 | 选择目标的排序策略 |
| 视角上限 | Float | 90 | 0~180 | 与视线夹角超过此值则不攻击 |
| 名字黑名单 | String | "" | - | 逗号分隔的实体名，部分匹配 |

**目标选择流程**（每帧 onTick）：

```
1. NativeBridge.nativeGetLocalPlayerPos() / nativeGetLocalPlayerRotation()
   ↓ 拿到玩家坐标和朝向
2. NativeBridge.nativeQueryEntities()
   ↓ 拿到所有实体（14-float-per-entity 扁平数组）
3. TargetSelector.select(entities, filter, ...)
   ↓ 过滤：排除自身、过滤类型、距离、视角、黑名单
   ↓ 排序：按 priority (距离/角度/血量/护甲)
4. 取首位目标
   ↓
5. 视角模式 → nativeSetRotationSilent()
   ↓
6. CPS 冷却检查 (1000ms / cps)
   ↓
7. 穿墙检查 → nativeIsLineOfSightClear()
   ↓
8. NativeBridge.nativeAttackEntity(targetId, swingHand=true)
```

**当前状态**：所有 native 调用已接入 `owb::GameHooks`。
GameHooks 在 `game_offsets.h` 偏移未填时进入"安全降级"模式——
不会崩溃，但读不到真实实体数据、`attackEntity` 返回 false。
填入真实偏移/pattern 后即可激活，无需改 Kotlin 层。
在菜单底部会显示当前 KillAura 锁定的目标信息。

## 后续开发路线

| 优先级 | 任务 | 说明 |
|---|---|---|
| P0 | 填入真实偏移/pattern | 在 IDA/Ghidra 中分析 libminecraftpe.so，填入 game_offsets.h / GamePatterns |
| P0 | 实体名解析 | 让 GameEntity.name 显示真实名字（玩家名/怪物名） |
| P0 | LocalPlayer 捕获 | 通过 hook Minecraft::tick 或 Level::tick 拿到 s_localPlayer 指针 |
| P1 | 颜色 Bean 编辑器 | 接入 ImGui ColorEdit4 |
| P1 | 按键 Bean 录制 | 监听下次按键作为绑定 |
| P2 | ESP 渲染 | 在 overlay 上绘制玩家方框/血条，需要游戏相机矩阵 |
| P3 | 更多战斗模块 | Velocity (击退)、Criticals、AutoSoup 等 |
| P3 | 移动模块 | Flight、Speed、NoFall |

## 设计决策

- **不复刻原版卡密系统**：本项目定位为开源学习项目，不设卡密
- **不做账号登录**：避免合规风险
- **纯 Kotlin + 现代 NDK**：不依赖 DexClassLoader 重载自身
- **GLES3 而非 Vulkan**：兼容性优先，ImGui OpenGL3 后端成熟
- **arm64-v8a only**：与目标游戏一致
- **ImGui 作为 submodule**：版本固定 v1.90.4，CI 自动拉取
- **Dobby 作为 inline hook 引擎**：成熟、跨架构（arm64）、API 简洁；不使用 ModSDK
- **偏移集中管理**：所有版本相关常量集中在 `game_offsets.h`，便于多版本适配
- **dlsym 优先 + pattern 兜底**：游戏未 strip 时直接 dlsym，strip 后用字节模式扫描

## 接入真实游戏

### 当前现状：网易 x19 libminecraftpe.so 被符号加密加固

经分析，com.netease.x19 的 `libminecraftpe.so`（约 334MB，ARM64）的 ELF section 名
和 dynsym 中所有 77000+ 符号都被加密成乱码（如 `_+45zIix4j8]6#XI`），
**无法用 readelf/nm/strings 提取到任何有意义的游戏类符号**。

这是网易专门做的加固，目的是逼迫 Mod 开发者从他们的付费服务器拉偏移，
而不是本地静态分析。因此传统的"IDA 打开 → 找符号"流程不适用。

### 推荐流程：用 DumpModule 运行时分析

项目内置了 `DumpModule`（在菜单"调试"Tab），用于在游戏进程内运行时分析 .so：

1. **安装 OpenWorldBox 到设备**，启动游戏

2. **打开菜单**（音量上键）→ 调试 Tab → 展开 "Dump 工具" 选项

3. **点击"全量扫描"**——一次性执行：
   - `dl_iterate_phdr` 枚举所有已加载 .so（找 libminecraftpe.so 基址/大小）
   - 批量 dlsym 16 个候选符号（验证哪些未 strip）
   - 字符串扫描（在 .so 内存范围搜 "Player"/"attack"/"Level" 等关键词）

4. **看 logcat**：
   ```bash
   adb logcat -s OpenWorldBox-Dump
   ```
   输出包含：
   - 模块基址/大小/路径
   - 哪些符号 dlsym 成功（地址）/ 失败
   - 字符串匹配的偏移（如 `off=0x12345678: Player::attack`）

5. **根据结果决定下一步**：
   - **若有符号 dlsym 成功**：直接填入 `game_offsets.h` 或 `GamePatterns`
   - **若全部失败**：用"扫描自定义关键词"找其他线索（如函数名、错误消息字符串）
     再用"Dump 内存"在对应偏移 hex dump，人工识别 pattern

6. **填入偏移后重新编译**：
   ```bash
   ./gradlew assembleDebug
   ```

### DumpModule 菜单按钮清单

| 按钮 | 作用 |
|---|---|
| 全量扫描 | 一次性执行模块列表 + 候选符号 + 关键字符串扫描 |
| 枚举模块 | 只输出已加载 .so 列表 |
| 常用符号 dlsym | 批量 dlsym 16 个 MC 标准符号 |
| 解析自定义符号 | dlsym "自定义符号" Bean 中的字符串 |
| 扫描自定义关键词 | 在 .so 内存搜 "自定义关键词" Bean |
| Dump 内存 | hex dump "内存偏移" Bean 处 "dump 大小" 字节 |

所有结果走 logcat（tag: `OpenWorldBox-Dump`），避免在 ImGui 菜单中显示大量文本。

### 兜底方案

如果运行时 dump 仍拿不到足够信息，可考虑：

1. **Ghidra 自动化分析**：用 Ghidra Headless Analyzer 在 .so 上跑反编译，
   虽然符号加密但部分函数体逻辑可识别（RTTI、字符串引用、控制流）

2. **ModSDK 桥接**：用网易官方 ModSDK（clientApi）做合规功能，
   仅对 SDK 不暴露的能力（如攻击）走 inline hook

3. **多版本适配**：不同游戏版本偏移可能不同，
   建议按版本号维护多套偏移表（`GameVersion::detect()` 已预留接口）

## 新增模块示例

```kotlin
object MyModule : Module(
    id = "my_module",
    displayName = "我的模块",
    category = Category.RENDER,
    description = "示例：自定义模块"
) {
    private val speed = option(FloatBean(
        key = "speed",
        displayName = "速度",
        defaultValue = 1.0f,
        min = 0.1f,
        max = 10f
    ))

    override fun onEnabled() {
        Logger.i("MyModule 启用，速度=${speed.get()}")
    }

    override fun onDisabled() {
        Logger.i("MyModule 禁用")
    }
}
```

然后在 `ModuleManager.registerDefaults()` 中添加：
```kotlin
fun registerDefaults() {
    register(ExampleModule)
    register(MyModule)  // ← 新增
}
```

菜单会自动出现"视觉"Tab + "我的模块"开关。

## License

MIT
