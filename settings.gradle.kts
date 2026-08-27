pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shizuku-API 官方仓库
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "NyaApp"
include(":app")
