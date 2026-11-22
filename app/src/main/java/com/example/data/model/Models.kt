package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val bcp47Tag: String,
    val isRtl: Boolean = false
) {
    TURKISH("tr", "Türkçe", "tr-TR"),
    ENGLISH("en", "English", "en-US"),
    GERMAN("de", "Deutsch", "de-DE"),
    FRENCH("fr", "Français", "fr-FR"),
    SPANISH("es", "Español", "es-ES"),
    ARABIC("ar", "العربية", "ar-SA", isRtl = true);

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: TURKISH
    }
}

enum class ThemeMode {
    SYSTEM, DARK, LIGHT;

    companion object {
        fun fromName(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: DARK
    }
}

enum class ResponseTone {
    FRIENDLY, PROFESSIONAL, CONCISE, CREATIVE;

    companion object {
        fun fromName(raw: String?): ResponseTone =
            entries.firstOrNull { it.name == raw } ?: FRIENDLY
    }
}

enum class AiEngineMode {
    AUTO, CHATGPT, NEMO_CLOUD;

    companion object {
        fun fromName(raw: String?): AiEngineMode =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

enum class ActionType {
    CHAT, VOICE_COMMAND, BACKUP, WIDGET, PERSONALIZATION, LANGUAGE;

    companion object {
        fun fromName(raw: String?): ActionType =
            entries.firstOrNull { it.name == raw } ?: CHAT
    }
}

enum class WidgetTheme {
    DARK, LIGHT, GLASS;

    companion object {
        fun fromName(raw: String?): WidgetTheme =
            entries.firstOrNull { it.name == raw } ?: DARK
    }
}

// --- Firestore Serialized Models (1:1 parity with firestore.rules) ---

@Keep
@IgnoreExtraProperties
data class UserProfileDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val preferredLanguage: String = "tr",
    val themeMode: String = "DARK",
    val responseTone: String = "FRIENDLY",
    val fastModeEnabled: Boolean = true,
    val autoSpeakEnabled: Boolean = false,
    val preferredEngine: String = "AUTO",
    val encryptedPersona: String? = null,
    val personaIv: String? = null,
    val e2eeSalt: String? = null,
    val e2eeVerifier: String? = null,
    val e2eeVerifierIv: String? = null,
    @ServerTimestamp val lastBackupAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ConversationDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val encryptedTitle: String = "",
    val titleIv: String = "",
    val encryptedPreview: String = "",
    val previewIv: String = "",
    val languageCode: String = "tr",
    val engineUsed: String = "NEMO_CLOUD",
    val messageCount: Long = 0L,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val encryptionVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class EncryptedMessageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val role: String = "user",
    val encryptedContent: String = "",
    val iv: String = "",
    val engineUsed: String = "NEMO_CLOUD",
    val latencyMs: Long = 0L,
    val voiceInitiated: Boolean = false,
    val encryptionVersion: Long = 1L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RecentActionDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val actionType: String = "CHAT",
    val encryptedTitle: String = "",
    val titleIv: String = "",
    val encryptedDetail: String = "",
    val detailIv: String = "",
    val targetConversationId: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

// --- Decrypted Domain / UI Models ---

data class UserPreferences(
    val displayName: String = "",
    val language: AppLanguage = AppLanguage.TURKISH,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val responseTone: ResponseTone = ResponseTone.FRIENDLY,
    val fastModeEnabled: Boolean = true,
    val autoSpeakEnabled: Boolean = false,
    val preferredEngine: AiEngineMode = AiEngineMode.AUTO,
    val customPersona: String = "",
    val interests: List<String> = listOf("Productivity", "Technology"),
    val lastBackupAtMillis: Long? = null,
    val hasCloudE2eeVerifier: Boolean = false
)

data class ChatConversation(
    val id: String,
    val title: String,
    val preview: String,
    val languageCode: String,
    val engineUsed: String,
    val messageCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val updatedAtMillis: Long
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val engineUsed: String,
    val latencyMs: Long,
    val ttftMs: Long = 0L,
    val voiceInitiated: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

data class RecentActionItem(
    val id: String,
    val actionType: ActionType,
    val title: String,
    val detail: String,
    val targetConversationId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class WidgetConfig(
    val theme: WidgetTheme = WidgetTheme.DARK,
    val showVoiceButton: Boolean = true,
    val showQuickChatButton: Boolean = true,
    val showRecentActionsButton: Boolean = true,
    val showRecentSummary: Boolean = true
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
