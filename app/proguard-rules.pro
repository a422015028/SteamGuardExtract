# =============================================================================
# SteamGuardExtract — Xposed 模块 R8 规则
# =============================================================================
# 入口类 keep 为种子但允许混淆改名；无参构造必须保留（框架反射实例化）。
# java_init.list 由 -adaptresourcefilecontents 同步改写为混淆后类名。
# XposedModuleInterface 回调方法因 override library 接口方法被 R8 自动保留。
# =============================================================================

-keep,allowobfuscation class com.muort.steamguard.SteamGuardEntry {
    <init>();
}

-keep,allowobfuscation class * extends io.github.libxposed.api.XposedModule

# libxposed API 为 compileOnly 桩，不会打进 APK，忽略引用警告
-dontwarn io.github.libxposed.api.**

# 混淆后自动改写 java_init.list 中的入口类全名
-adaptresourcefilecontents **.list
