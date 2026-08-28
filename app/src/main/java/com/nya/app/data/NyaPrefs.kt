package com.nya.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
 * - IDLE：用户停顿指定时间后追加（实时模式）
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
        val IDLE_DELAY_MS = intPreferencesKey("idle_delay_ms")
        val PUNCTUATION_DELAY_MS = intPreferencesKey("punctuation_delay_ms")
        val KAOMOJI_ENABLED = booleanPreferencesKey("kaomoji_enabled")
        val CUSTOM_KAOMOJIS = stringPreferencesKey("custom_kaomojis")
    }

    // ============================
    //  默认喵颜文字库（与"喵/猫"关联，可爱风格）
    // ============================
    companion object {
        val DEFAULT_KAOMOJIS: List<String> = listOf(
            "(=^･ω･^=)",
            "(=´ㅅ`=)",
            "(=๑>◡<๑=)",
            "(=^･ｪ･^=)",
            "(=^･^=)",
            "(=・ω・=)",
            "(≈>ܫ<≈)",
            "(=^‥^=)",
            "(₌ↀωↀ₌)",
            "(=｀ω´=)",
            "(=^-ω-^=)",
            "(^..^)",
            "(=^･ｪ･^)/",
            "=^.^=",
            "(=^･ω･^)ﾉ",
            "ฅ(^・ω・^ฅ)",
            "(ฅ´ω`ฅ)",
            "ฅ(ㅇㅅㅇ❀)ฅ",
            "(=^･ω･^)ノ♡",
            "ฅ^•ﻌ•^ฅ",
            "(=ΦωΦ=)",
            "(๑ↀᆺↀ๑)",
            "(>^ω^<)",
            "ฅ(•ㅅ•❀)ฅ",
            "(｡･ω･｡)ﾉ♡"
        )
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

    /** 停顿追加模式延迟时间（毫秒），默认 1200ms，范围 300~3000 */
    val idleDelayMs: Flow<Int> = context.dataStore.data
        .map { p -> (p[Keys.IDLE_DELAY_MS] ?: 1200).coerceIn(300, 5000) }
        .stateIn(scope, SharingStarted.Eagerly, 1200)

    /** 标点追加模式延迟时间（毫秒），默认 700ms，范围 200~3000 */
    val punctuationDelayMs: Flow<Int> = context.dataStore.data
        .map { p -> (p[Keys.PUNCTUATION_DELAY_MS] ?: 700).coerceIn(200, 5000) }
        .stateIn(scope, SharingStarted.Eagerly, 700)

    /** 是否在追加内容后随机追加一个喵颜文字 */
    val kaomojiEnabled: Flow<Boolean> = context.dataStore.data
        .map { p -> p[Keys.KAOMOJI_ENABLED] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 用户自定义颜文字库（多行，每行一个；空串表示使用默认库） */
    val customKaomojis: Flow<String> = context.dataStore.data
        .map { p -> p[Keys.CUSTOM_KAOMOJIS] ?: "" }
        .stateIn(scope, SharingStarted.Eagerly, "")

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

    suspend fun setIdleDelayMs(ms: Int) {
        context.dataStore.edit { it[Keys.IDLE_DELAY_MS] = ms.coerceIn(300, 5000) }
    }

    suspend fun setPunctuationDelayMs(ms: Int) {
        context.dataStore.edit { it[Keys.PUNCTUATION_DELAY_MS] = ms.coerceIn(200, 5000) }
    }

    suspend fun setKaomojiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KAOMOJI_ENABLED] = enabled }
    }

    suspend fun setCustomKaomojis(raw: String) {
        context.dataStore.edit { it[Keys.CUSTOM_KAOMOJIS] = raw }
    }

    fun snapshotBlocking(): Snapshot {
        val fallback = Snapshot(true, "喵", emptySet(), true, AppendMode.IDLE, 1200, 700, false, "")
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
            },
            idleDelayMs = (prefs[Keys.IDLE_DELAY_MS] ?: 1200).coerceIn(300, 5000),
            punctuationDelayMs = (prefs[Keys.PUNCTUATION_DELAY_MS] ?: 700).coerceIn(200, 5000),
            kaomojiEnabled = prefs[Keys.KAOMOJI_ENABLED] ?: false,
            customKaomojis = prefs[Keys.CUSTOM_KAOMOJIS] ?: ""
        )
    }

    data class Snapshot(
        val masterEnabled: Boolean,
        val appendContent: String,
        val whitelistPackages: Set<String>,
        val isGlobalMode: Boolean,
        val appendMode: AppendMode,
        val idleDelayMs: Int,
        val punctuationDelayMs: Int,
        val kaomojiEnabled: Boolean,
        val customKaomojis: String
    )
}
