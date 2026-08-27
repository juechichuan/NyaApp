package com.nya.app

import android.app.Application
import android.content.Context
import com.nya.app.data.NyaPrefs
import com.nya.app.shizuku.ShizukuManager
import rikka.shizuku.Shizuku

class NyaApplication : Application() {

    companion object {
        lateinit var instance: NyaApplication
            private set
    }

    lateinit var prefs: NyaPrefs
        private set
    lateinit var shizuku: ShizukuManager
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        instance = this
        // Shizuku Provider 初始化（官方要求尽早调用）
        Shizuku.onCreate(base)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = NyaPrefs(this)
        shizuku = ShizukuManager(this)
    }
}
