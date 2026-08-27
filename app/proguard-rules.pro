# 保留无障碍服务类（反射调用）
-keep class com.nya.app.service.** { *; }
-keep class com.nya.app.NyaApplication { *; }

# Compose
-dontwarn androidx.compose.**

# Kotlin
-dontwarn kotlin.**
