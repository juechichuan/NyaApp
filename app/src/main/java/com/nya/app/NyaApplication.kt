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
        // ShizukuProvider 会在被系统加载时自动初始化，无需手动调用
    }

    override fun onCreate() {
        super.onCreate()
        prefs = NyaPrefs(this)
        shizuku = ShizukuManager(this)
    }
}
