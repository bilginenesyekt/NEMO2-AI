package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.crypto.E2eeCryptoManager
import com.example.data.model.ActionType
import com.example.data.model.AiEngineMode
import com.example.data.model.AppLanguage
import com.example.data.model.RecentActionItem
import com.example.data.model.ThemeMode
import com.example.data.model.WidgetConfig
import com.example.data.model.WidgetTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.nemoDataStore by preferencesDataStore(name = "nemo_preferences")

class PreferencesStore(
    private val context: Context,
    private val crypto: E2eeCryptoManager
) {
    private val appContext = context.applicationContext

    val themeModeFlow: Flow<ThemeMode> = appContext.nemoDataStore.data.map { prefs ->
        ThemeMode.fromName(prefs[KEY_THEME_MODE])
    }

    val languageFlow: Flow<AppLanguage> = appContext.nemoDataStore.data.map { prefs ->
        AppLanguage.fromCode(prefs[KEY_LANGUAGE_CODE])
    }

    val widgetConfigFlow: Flow<WidgetConfig> = appContext.nemoDataStore.data.map { prefs ->
        WidgetConfig(
            theme = WidgetTheme.fromName(prefs[KEY_WIDGET_THEME]),
            showVoiceButton = prefs[KEY_WIDGET_VOICE] ?: true,
            showQuickChatButton = prefs[KEY_WIDGET_CHAT] ?: true,
            showRecentActionsButton = prefs[KEY_WIDGET_RECENT] ?: true,
            showRecentSummary = prefs[KEY_WIDGET_SUMMARY] ?: true
        )
    }

    val customOpenAiKeyFlow: Flow<String> = appContext.nemoDataStore.data.map { prefs ->
        val cipher = prefs[KEY_OPENAI_CIPHER].orEmpty()
        val iv = prefs[KEY_OPENAI_IV].orEmpty()
        if (cipher.isNotBlank() && iv.isNotBlank()) crypto.decrypt(cipher, iv) else ""
    }

    val cachedRecentActionsFlow: Flow<List<RecentActionItem>> = appContext.nemoDataStore.data.map { prefs ->
        val cipher = prefs[KEY_RECENT_CIPHER].orEmpty()
        val iv = prefs[KEY_RECENT_IV].orEmpty()
        if (cipher.isBlank() || iv.isBlank()) {
            emptyList()
        } else {
            decodeRecentActions(crypto.decrypt(cipher, iv))
        }
    }

    suspend fun getThemeModeSnapshot(): ThemeMode = themeModeFlow.first()
    suspend fun getLanguageSnapshot(): AppLanguage = languageFlow.first()
    suspend fun getWidgetConfigSnapshot(): WidgetConfig = widgetConfigFlow.first()
    suspend fun getCachedRecentActionsSnapshot(): List<RecentActionItem> = cachedRecentActionsFlow.first()
    suspend fun getCustomOpenAiKeySnapshot(): String = customOpenAiKeyFlow.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        appContext.nemoDataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        appContext.nemoDataStore.edit { it[KEY_LANGUAGE_CODE] = language.code }
    }

    suspend fun setFastMode(enabled: Boolean) {
        appContext.nemoDataStore.edit { it[KEY_FAST_MODE] = enabled }
    }

    suspend fun setAutoSpeak(enabled: Boolean) {
        appContext.nemoDataStore.edit { it[KEY_AUTO_SPEAK] = enabled }
    }

    suspend fun setEngineMode(mode: AiEngineMode) {
        appContext.nemoDataStore.edit { it[KEY_ENGINE_MODE] = mode.name }
    }

    suspend fun setWidgetConfig(config: WidgetConfig) {
        appContext.nemoDataStore.edit { prefs ->
            prefs[KEY_WIDGET_THEME] = config.theme.name
            prefs[KEY_WIDGET_VOICE] = config.showVoiceButton
            prefs[KEY_WIDGET_CHAT] = config.showQuickChatButton
            prefs[KEY_WIDGET_RECENT] = config.showRecentActionsButton
            prefs[KEY_WIDGET_SUMMARY] = config.showRecentSummary
        }
    }

    suspend fun saveCustomOpenAiKey(rawKey: String) {
        appContext.nemoDataStore.edit { prefs ->
            val trimmed = rawKey.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(KEY_OPENAI_CIPHER)
                prefs.remove(KEY_OPENAI_IV)
            } else {
                val encrypted = crypto.encrypt(trimmed)
                prefs[KEY_OPENAI_CIPHER] = encrypted.ciphertextB64
                prefs[KEY_OPENAI_IV] = encrypted.ivB64
            }
        }
    }

    suspend fun cacheRecentActions(items: List<RecentActionItem>) {
        val top5 = items.take(5)
        val arr = JSONArray()
        top5.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("actionType", item.actionType.name)
                    put("title", item.title)
                    put("detail", item.detail)
                    if (!item.targetConversationId.isNullOrBlank()) {
                        put("targetConversationId", item.targetConversationId)
                    }
                    put("createdAtMillis", item.createdAtMillis)
                }
            )
        }
        val encrypted = crypto.encrypt(arr.toString())
        appContext.nemoDataStore.edit { prefs ->
            prefs[KEY_RECENT_CIPHER] = encrypted.ciphertextB64
            prefs[KEY_RECENT_IV] = encrypted.ivB64
        }
    }

    private fun decodeRecentActions(json: String): List<RecentActionItem> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        RecentActionItem(
                            id = obj.optString("id"),
                            actionType = ActionType.fromName(obj.optString("actionType")),
                            title = obj.optString("title"),
                            detail = obj.optString("detail"),
                            targetConversationId = obj.optString("targetConversationId").takeIf { it.isNotBlank() },
                            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_LANGUAGE_CODE = stringPreferencesKey("language_code")
        private val KEY_FAST_MODE = booleanPreferencesKey("fast_mode")
        private val KEY_AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        private val KEY_ENGINE_MODE = stringPreferencesKey("engine_mode")
        private val KEY_WIDGET_THEME = stringPreferencesKey("widget_theme")
        private val KEY_WIDGET_VOICE = booleanPreferencesKey("widget_voice")
        private val KEY_WIDGET_CHAT = booleanPreferencesKey("widget_chat")
        private val KEY_WIDGET_RECENT = booleanPreferencesKey("widget_recent")
        private val KEY_WIDGET_SUMMARY = booleanPreferencesKey("widget_summary")
        private val KEY_OPENAI_CIPHER = stringPreferencesKey("openai_cipher")
        private val KEY_OPENAI_IV = stringPreferencesKey("openai_iv")
        private val KEY_RECENT_CIPHER = stringPreferencesKey("recent_cipher")
        private val KEY_RECENT_IV = stringPreferencesKey("recent_iv")
    }
}
