# =========================================================
#  NyaApp ProGuard / R8 加固规则
#  目标：最大化混淆 + 资源压缩，让反编译后代码难读
#  同时保留系统必须反射调用的类（无障碍服务、Application、FileProvider）
# =========================================================

# ---------- 通用优化 ----------
# 不做预校验（Android 上由 ART 完成，节省构建时间）
-dontpreverify
# 启用优化（release 模式下 R8 默认带，这里显式声明）
-optimizations !code/simplification/arithmetic,!field/*,class/merging/*
-optimizationpasses 5
-allowaccessmodification
# 重用混淆名，进一步压缩 dex
-overloadaggressively

# ---------- 安全相关：混淆敏感包名 ----------
# 反篡改保护类全部混淆，但保留对外接口（这里没有反射调用，可全部混淆）
# -keep class com.nya.app.security.SignatureGuard { *; }  ← 不要这样写！要让它被混淆
# SignatureGuard 通过 attachBaseContext 直接调用，没有反射，可以全部混淆

# ---------- 必须保留的入口 ----------
# Application 类（AndroidManifest name=".NyaApplication" 通过反射实例化）
-keep class com.nya.app.NyaApplication {
    public <init>();
    public static *;
}
# 无障碍服务（系统通过反射实例化）
-keep class com.nya.app.service.NyaAccessibilityService { *; }
-keep class com.nya.app.service.NyaForegroundService { *; }

# Activity 类（AndroidManifest 注册的）
-keep class com.nya.app.ui.MainActivity { *; }
-keep class com.nya.app.ui.AboutActivity { *; }
-keep class com.nya.app.ui.AppPickerActivity { *; }

# FileProvider 子类（这里用的是 androidx.core.content.FileProvider 本身，无需保留自定义子类）

# ---------- Compose / Kotlin / AndroidX ----------
-dontwarn androidx.compose.**
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**

# Kotlin metadata（反射用不到，可以剥离，让反编译看不到函数原名）
-assumenosideeffects class kotlin.Metadata { *; }

# DataStore / Preferences（涉及反射序列化）
-keep class androidx.datastore.** { *; }
-keep class com.nya.app.data.NyaPrefs$* { *; }
-keepclassmembers class com.nya.app.data.NyaPrefs$* {
    *** Companion;
}

# Coroutines（内部状态机，部分版本 R8 需要保留）
-dontwarn kotlinx.coroutines.**

# ---------- AndroidX 通用 ----------
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.core.app.** { *; }

# ---------- 反篡改保护：去掉 Log 调用 ----------
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ---------- 防止枚举被混淆（Compose 内部用枚举做反射概率高） ----------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- Native 方法保留 ----------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- Parcelable CREATOR（系统通过反射读） ----------
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ---------- Serializable ----------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
