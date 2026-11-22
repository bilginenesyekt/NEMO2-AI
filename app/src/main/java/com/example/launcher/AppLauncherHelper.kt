package com.example.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.util.Locale

sealed interface AppLaunchResult {
    data class Launched(val appLabel: String) : AppLaunchResult
    data class NotFound(val query: String) : AppLaunchResult
    data class Failed(val query: String, val reason: String) : AppLaunchResult
}

object AppLauncherHelper {

    private const val TAG = "AppLauncher"

    private val knownPackages = mapOf(
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "harita" to "com.google.android.apps.maps",
        "haritalar" to "com.google.android.apps.maps",
        "telegram" to "org.telegram.messenger",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "tiktok" to "com.zhiliaoapp.musically",
        "netflix" to "com.netflix.mediaclient",
        "play store" to "com.android.vending",
        "google play" to "com.android.vending"
    )

    fun launchAppByQuery(context: Context, rawQuery: String): AppLaunchResult {
        val normalized = normalizeQuery(rawQuery)
        if (normalized.isBlank()) return AppLaunchResult.NotFound(rawQuery)
        val candidates = buildQueryCandidates(normalized)

        // 1. Check built-in Android system intents first (Camera, Settings, Clock, Dialer, etc.)
        candidates.firstNotNullOfOrNull { resolveSystemIntent(it) }?.let { (label, intent) ->
            return startActivitySafe(context, intent, label, rawQuery)
        }

        val pm = context.packageManager

        // 2. Check well-known package IDs
        val knownMatch = candidates.firstNotNullOfOrNull { candidate ->
            knownPackages.entries.firstOrNull { (key, _) ->
                candidate == key || candidate.contains(key)
            }
        }
        if (knownMatch != null) {
            val (key, pkg) = knownMatch
            pm.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
                val label = resolveAppLabel(pm, pkg) ?: key.replaceFirstChar { it.uppercase() }
                return startActivitySafe(context, launchIntent, label, rawQuery)
            }
        }

        // 3. Query launcher activities visible via <queries> in AndroidManifest.xml
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvedList = try {
            pm.queryIntentActivities(mainIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            Log.w(TAG, "queryIntentActivities failed", e)
            emptyList()
        }

        val bestMatch = resolvedList.firstOrNull { info ->
            val label = normalizeQuery(info.loadLabel(pm)?.toString().orEmpty())
            label.isNotEmpty() && candidates.any { candidate ->
                label == candidate || label.contains(candidate) || candidate.contains(label)
            }
        }

        if (bestMatch != null) {
            val pkg = bestMatch.activityInfo.packageName
            val label = bestMatch.loadLabel(pm)?.toString() ?: pkg
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(pkg, bestMatch.activityInfo.name)
            }
            return startActivitySafe(context, launchIntent, label, rawQuery)
        }

        return AppLaunchResult.NotFound(rawQuery.trim())
    }

    private fun resolveSystemIntent(normalized: String): Pair<String, Intent>? {
        return when {
            matchesAny(normalized, "kamera", "camera", "foto") ->
                "Kamera" to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            matchesAny(normalized, "telefon ayarlari", "sistem ayarlari", "cihaz ayarlari", "device settings", "system settings") ->
                "Ayarlar" to Intent(Settings.ACTION_SETTINGS)
            matchesAny(normalized, "wifi", "wi-fi") ->
                "Wi-Fi" to Intent(Settings.ACTION_WIFI_SETTINGS)
            matchesAny(normalized, "bluetooth") ->
                "Bluetooth" to Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            matchesAny(normalized, "saat", "alarm", "clock") ->
                "Saat" to Intent(AlarmClock.ACTION_SHOW_ALARMS)
            matchesAny(normalized, "telefon", "arama", "dialer", "phone") ->
                "Telefon" to Intent(Intent.ACTION_DIAL)
            matchesAny(normalized, "tarayici", "browser", "internet") ->
                "Tarayıcı" to Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            matchesAny(normalized, "hesap makinesi", "calculator", "rechner") ->
                "Hesap Makinesi" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR)
            matchesAny(normalized, "takvim", "calendar", "kalender") ->
                "Takvim" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            matchesAny(normalized, "mesajlar", "sms", "messages") ->
                "Mesajlar" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
            matchesAny(normalized, "rehber", "kisiler", "contacts") ->
                "Rehber" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
            matchesAny(normalized, "galeri", "fotograflar", "gallery", "photos") ->
                "Galeri" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
            matchesAny(normalized, "muzik", "music") ->
                "Müzik" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
            else -> null
        }
    }

    private fun startActivitySafe(
        context: Context,
        intent: Intent,
        label: String,
        rawQuery: String
    ): AppLaunchResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            AppLaunchResult.Launched(label)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch $label", e)
            AppLaunchResult.Failed(rawQuery, e.localizedMessage ?: "Cannot open $label")
        }
    }

    private fun resolveAppLabel(pm: PackageManager, packageName: String): String? {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun matchesAny(haystack: String, vararg terms: String): Boolean =
        terms.any { haystack == it || haystack.contains(it) }

    internal fun normalizeQuery(input: String): String {
        return input
            .lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('İ', 'i')
            .replace('ş', 's')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace("'", " ")
            .replace("’", " ")
            .replace(Regex("\\b(uygulamasini|uygulamasi|uygulama|app|ac|launch|open|start|baslat|lutfen|nemo|nemoyu|su)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    internal fun buildQueryCandidates(normalized: String): List<String> {
        val list = linkedSetOf(normalized)
        if (normalized.length > 4) {
            list.add(normalized.replace(Regex("(?:yi|yu|ya|ye|ni|nu|na|ne)$"), "").trim())
            list.add(normalized.replace(Regex("(?:i|u|a|e)$"), "").trim())
        }
        return list.filter { it.isNotBlank() }
    }
}
