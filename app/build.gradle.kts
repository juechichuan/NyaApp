import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nya.app"
    compileSdk = 35 // Android 15（CI 环境稳定可用，App 在 Android 16 上正常运行）

    defaultConfig {
        applicationId = "com.nya.app"
        minSdk = 24 // Android 7.0，兼容绝大多数设备
        targetSdk = 35 // Android 15（向上兼容 Android 16）
        // 版本号规则：小更新+0.01，大更新+0.1
        // 1.55 → 1.56 大更新(+0.1)：APK 加固（运行时签名校验 + 防调试 + R8 完整混淆 + release 正式签名）
        versionCode = 21
        versionName = "1.56"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // 必须放在 buildTypes 之前，便于在 release buildType 里引用 nyaRelease
    signingConfigs {
        create("nyaRelease") {
            // CI 通过环境变量注入 keystore；本地通过 gradle.properties 注入
            val ksFile = providers.gradleProperty("NYA_KEYSTORE_FILE").orNull
                ?: System.getenv("NYA_KEYSTORE_FILE")
            val ksPwd = providers.gradleProperty("NYA_KEYSTORE_PASSWORD").orNull
                ?: System.getenv("NYA_KEYSTORE_PASSWORD")
            val ksAlias = providers.gradleProperty("NYA_KEY_ALIAS").orNull
                ?: System.getenv("NYA_KEY_ALIAS")
            val kPwd = providers.gradleProperty("NYA_KEY_PASSWORD").orNull
                ?: System.getenv("NYA_KEY_PASSWORD")
            if (ksFile != null && File(ksFile).exists() && ksPwd != null && ksAlias != null && kPwd != null) {
                storeFile = File(ksFile)
                storePassword = ksPwd
                keyAlias = ksAlias
                keyPassword = kPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // release 必须用固定 keystore 签名，确保 SignatureGuard 校验通过
            // 若 nyaRelease 未配置则回退到 debug 签名（仅本地开发场景）
            signingConfig = signingConfigs.findByName("nyaRelease")
                ?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 基础 AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // DataStore（偏好设置）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
