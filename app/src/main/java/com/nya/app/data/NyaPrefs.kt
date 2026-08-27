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
 * 自动追加模式
 * - IDLE：用户停顿 1.2s 后追加（实时模式）
 * - PUNCTUATION：仅在标点符号后追加
 */
enum class AppendMode { IDLE, PUNCTUATION }

class NyaPrefs(private val context: Context) {

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val APPEND_CONTENT = stringPreferencesKey("append_content")
        val WHITELIST_PACKAGES = stringSetPreferencesKey("whitelist_packages")
        val IS_GLOBAL_MODE = booleanPreferencesKey("is_global_mode")
        val APPEND_MODE = stringPreferencesKey("append_mode")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val masterEnabled: Flow<Boolean> = context.dataStore.data
        .map { p -> p[Keys.MASTER_ENABLED] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val appendContent: Flow<String> = context.dataStore.data
        .map { p -> p[Keys.APPEND_CONTENT] ?: "喵" }
        .stateIn(scope, SharingStarted.Eagerly, "喵")

    val whitelistPackages: Flow<Set<String>> = context.dataStore.data
        .map { p -> p[Keys.WHITELIST_PACKAGES] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // 全局模式：true=对所有App生效，false=仅白名单中的App生效
    val isGlobalMode: Flow<Boolean> = context.dataStore.data
        .map { p -> p[Keys.IS_GLOBAL_MODE] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    val appendMode: Flow<AppendMode> = context.dataStore.data
        .map { p ->
            when (p[Keys.APPEND_MODE]) {
                "PUNCTUATION" -> AppendMode.PUNCTUATION
                else -> AppendMode.IDLE
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, AppendMode.IDLE)

    suspend fun setMasterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MASTER_ENABLED] = enabled }
    }

    suspend fun setAppendContent(content: String) {
        context.dataStore.edit { it[Keys.APPEND_CONTENT] = content }
    }

    suspend fun setWhitelistPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.WHITELIST_PACKAGES] = packages }
    }

    suspend fun setGlobalMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IS_GLOBAL_MODE] = enabled }
    }

    suspend fun setAppendMode(mode: AppendMode) {
        context.dataStore.edit { it[Keys.APPEND_MODE] = mode.name }
    }

    fun snapshotBlocking(): Snapshot {
        val fallback = Snapshot(true, "喵", emptySet(), true, AppendMode.IDLE)
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
            whitelistPackages = prefs[Keys.WHITELIST_PACKAGES] ?: emptySet(),
            isGlobalMode = prefs[Keys.IS_GLOBAL_MODE] ?: true,
            appendMode = when (prefs[Keys.APPEND_MODE]) {
                "PUNCTUATION" -> AppendMode.PUNCTUATION
                else -> AppendMode.IDLE
            }
        )
    }

    data class Snapshot(
        val masterEnabled: Boolean,
        val appendContent: String,
        val whitelistPackages: Set<String>,
        val isGlobalMode: Boolean,
        val appendMode: AppendMode
    )
}
