package com.nya.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nya_prefs")

/**
 * 全局偏好设置（基于 DataStore）。
 *
 * 注意：Service 启动时可能还没进入协程世界，因此提供 [snapshotBlocking] 作为一次性同步读取。
 * 它复用同一个 DataStore 实例（不会重复创建文件），使用 runBlocking + 超时保证不会 ANR。
 */
class NyaPrefs(private val context: Context) {

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val APPEND_CONTENT = stringPreferencesKey("append_content")
        val WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 总开关
    val masterEnabled: Flow<Boolean> = context.dataStore.data
        .map { p -> p[Keys.MASTER_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    // 追加内容（默认"喵"）
    val appendContent: Flow<String> = context.dataStore.data
        .map { p -> p[Keys.APPEND_CONTENT] ?: "喵" }
        .stateIn(scope, SharingStarted.Eagerly, "喵")

    // 生效白名单（包名集合）
    val whitelistPackages: Flow<Set<String>> = context.dataStore.data
        .map { p -> p[Keys.WHITELIST_PACKAGES] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun setMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MASTER_ENABLED] = enabled }
    }

    suspend fun setAppendContent(content: String) {
        context.dataStore.edit { it[Keys.APPEND_CONTENT] = content }
    }

    suspend fun setWhitelistPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.WHITELIST_PACKAGES] = packages }
    }

    /** 一次性阻塞读取当前值（最多等 800ms，用于 Service onCreate 早期） */
    fun snapshotBlocking(): Snapshot {
        val fallback = Snapshot(true, "喵", emptySet())
        val prefs: Preferences? = runCatching {
            runBlocking {
                withTimeoutOrNull(800) {
                    context.dataStore.data.first()
                }
            }
        }.getOrNull()
        if (prefs == null) return fallback
        return Snapshot(
            masterEnabled = prefs[Keys.MASTER_ENABLED] ?: true,
            appendContent = prefs[Keys.APPEND_CONTENT] ?: "喵",
            whitelistPackages = prefs[Keys.WHITELIST_PACKAGES] ?: emptySet()
        )
    }

    data class Snapshot(
        val masterEnabled: Boolean,
        val appendContent: String,
        val whitelistPackages: Set<String>
    )
}
