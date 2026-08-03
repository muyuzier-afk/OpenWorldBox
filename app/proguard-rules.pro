# Xposed 入口类不能被混淆
-keep class com.openworldbox.core.HookInit { *; }
-keep class com.openworldbox.core.NativeBridge { *; }

# 所有 native 方法（自动保留，但保险起见）
-keepclasseswithmembernames class * {
    native <methods>;
}

# 被 native 反射调用的类
-keep class com.openworldbox.ui.RenderOverlay { *; }
-keep class com.openworldbox.ui.GameActivityOverlayHolder { *; }
-keep class com.openworldbox.core.GameActivity { *; }

# Xposed API
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# Kotlin metadata
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
