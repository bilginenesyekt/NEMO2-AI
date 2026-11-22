package com.example.voice

import com.example.data.model.ThemeMode
import java.util.Locale

sealed interface VoiceIntent {
    data object ShowRecentActions : VoiceIntent
    data class OpenExternalApp(val appQuery: String) : VoiceIntent
    data object NewChat : VoiceIntent
    data class SetTheme(val mode: ThemeMode) : VoiceIntent
    data class ToggleFastMode(val enabled: Boolean) : VoiceIntent
    data object BackupNow : VoiceIntent
    data object OpenSettings : VoiceIntent
    data object OpenHistory : VoiceIntent
    data class SendChatPrompt(val prompt: String) : VoiceIntent
}

object VoiceCommandRouter {

    private val openAppRegexList = listOf(
        // Turkish explicit: "Nemoyu şu uygulamayı aç: YouTube", "Şu uygulamayı aç WhatsApp"
        Regex("^(?:nemoyu\\s+|nemo\\s+)?(?:su\\s+uygulamayi\\s+ac[:\\s]+)(.+)$"),
        // Turkish: "YouTube uygulamasını aç", "WhatsApp uygulamasını başlat"
        Regex("^(?:nemoyu\\s+|nemo\\s+)?(.+?)\\s+uygulamas[ıi]n[ıi]?\\s+(?:ac|baslat)$"),
        // Turkish direct: "Kamerayı aç", "Spotify'ı aç", "Telefon ayarlarını aç"
        Regex("^(?:nemoyu\\s+|nemo\\s+)?(.+?)\\s+(?:ac|baslat)$"),
        // English: "Open YouTube", "Launch Spotify app", "Start Camera"
        Regex("^(?:nemo\\s+)?(?:open|launch|start)\\s+(.+?)(?:\\s+app)?$"),
        // German / French / Spanish
        Regex("^(?:nemo\\s+)?(?:oeffne|öffne|starte)\\s+(.+)$"),
        Regex("^(?:nemo\\s+)?(?:ouvre|lancer|abrir|abre)\\s+(.+)$")
    )

    fun parse(rawTranscript: String): VoiceIntent {
        val original = rawTranscript.trim()
        if (original.isEmpty()) return VoiceIntent.ShowRecentActions
        val text = normalizeTr(original)

        // Check explicit external app launch before generic "uygulamayi ac"
        val explicitExternalApp = extractExplicitAppTarget(text)
        if (explicitExternalApp != null) {
            return VoiceIntent.OpenExternalApp(explicitExternalApp)
        }

        return when {
            containsAny(
                text,
                "nemoyu ac", "nemo yu ac", "nemo ac", "uygulamayi ac", "son islem",
                "son islemlerimi", "son yaptiklarim",
                "open nemo", "open app", "recent action", "list recent", "last actions",
                "letzte aktivitaeten", "actions recentes", "acciones recientes"
            ) -> VoiceIntent.ShowRecentActions

            containsAny(
                text,
                "yeni sohbet", "sohbet baslat", "new chat", "start chat",
                "neuer chat", "nouvelle discussion", "nuevo chat"
            ) -> VoiceIntent.NewChat

            containsAny(
                text,
                "karanlik mod", "gece modu", "dark mode", "dunkel", "sombre", "oscuro"
            ) -> VoiceIntent.SetTheme(ThemeMode.DARK)

            containsAny(
                text,
                "acik mod", "gunduz modu", "light mode", "hell", "clair", "claro"
            ) -> VoiceIntent.SetTheme(ThemeMode.LIGHT)

            containsAny(
                text,
                "hizli mod", "fast mode", "schnellmodus", "mode rapide", "modo rapido"
            ) -> VoiceIntent.ToggleFastMode(true)

            containsAny(
                text,
                "yedekle", "bulut yedek", "back up", "backup", "sichern", "sauvegarder", "copia de seguridad"
            ) -> VoiceIntent.BackupNow

            containsAny(
                text,
                "telefon ayarlari", "sistem ayarlari", "cihaz ayarlari",
                "device settings", "system settings", "wifi ayarlari", "bluetooth ayarlari"
            ) -> VoiceIntent.OpenExternalApp(text)

            containsAny(
                text,
                "nemo ayarlari", "uygulama ayarlari", "merkezi ac", "open settings", "settings", "einstellungen", "parametres"
            ) || text == "ayarlar" || text == "ayarlari ac" -> VoiceIntent.OpenSettings

            containsAny(
                text,
                "gecmisi ac", "sohbet gecmisi", "gecmis", "open history", "history", "verlauf", "historique", "historial"
            ) -> VoiceIntent.OpenHistory

            else -> {
                val appTarget = extractAppQuery(text)
                if (appTarget != null) {
                    VoiceIntent.OpenExternalApp(appTarget)
                } else {
                    VoiceIntent.SendChatPrompt(original)
                }
            }
        }
    }

    private fun extractExplicitAppTarget(normalizedText: String): String? {
        val explicitRegexes = openAppRegexList.take(2)
        for (regex in explicitRegexes) {
            val match = regex.matchEntire(normalizedText) ?: continue
            val candidate = cleanCandidate(match.groupValues[1])
            if (isValidAppCandidate(candidate)) return candidate
        }
        return null
    }

    private fun extractAppQuery(normalizedText: String): String? {
        for (regex in openAppRegexList) {
            val match = regex.matchEntire(normalizedText)
            if (match != null) {
                val candidate = cleanCandidate(match.groupValues[1])
                if (isValidAppCandidate(candidate)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun cleanCandidate(raw: String): String {
        return raw.replace(Regex("['’:]"), "").trim()
    }

    private fun isValidAppCandidate(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        val reserved = setOf(
            "uygulamayi", "uygulama", "su uygulamayi", "nemo", "nemoyu",
            "sohbet", "ayarlar", "ayarlari", "gecmis", "gecmisi", "app"
        )
        return candidate !in reserved
    }

    private fun normalizeTr(input: String): String {
        return input
            .lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
