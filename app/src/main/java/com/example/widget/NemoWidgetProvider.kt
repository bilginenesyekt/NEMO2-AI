package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.crypto.E2eeCryptoManager
import com.example.data.local.PreferencesStore
import com.example.data.model.AppLanguage
import com.example.data.model.WidgetConfig
import com.example.data.model.WidgetTheme
import kotlinx.coroutines.runBlocking

class NemoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val EXTRA_LAUNCH_MODE = "nemo_launch_mode"
        const val MODE_RECENT = "recent"
        const val MODE_VOICE = "voice"
        const val MODE_CHAT = "chat"

        fun refreshAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NemoWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                for (id in ids) {
                    updateAppWidget(context, manager, id)
                }
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val crypto = E2eeCryptoManager(context)
            val store = PreferencesStore(context, crypto)
            val (config, recentItems, lang) = runBlocking {
                Triple(
                    store.getWidgetConfigSnapshot(),
                    store.getCachedRecentActionsSnapshot(),
                    store.getLanguageSnapshot()
                )
            }

            val views = RemoteViews(context.packageName, R.layout.widget_nemo)
            applyTheme(views, config)
            applyLabels(views, lang, recentItems.map { it.title }, config)

            views.setViewVisibility(
                R.id.btn_widget_voice,
                if (config.showVoiceButton) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.btn_widget_chat,
                if (config.showQuickChatButton) View.VISIBLE else View.GONE
            )
            views.setViewVisibility(
                R.id.btn_widget_recent,
                if (config.showRecentActionsButton) View.VISIBLE else View.GONE
            )

            views.setOnClickPendingIntent(
                R.id.btn_widget_voice,
                buildLaunchPendingIntent(context, "nemo://voice", MODE_VOICE, 101)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_chat,
                buildLaunchPendingIntent(context, "nemo://chat", MODE_CHAT, 102)
            )
            views.setOnClickPendingIntent(
                R.id.btn_widget_recent,
                buildLaunchPendingIntent(context, "nemo://recent", MODE_RECENT, 103)
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                buildLaunchPendingIntent(context, "nemo://recent", MODE_RECENT, 100)
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun applyTheme(views: RemoteViews, config: WidgetConfig) {
            when (config.theme) {
                WidgetTheme.DARK -> {
                    views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.bg_widget_dark)
                    views.setTextColor(R.id.widget_title, Color.parseColor("#00E5FF"))
                    views.setTextColor(R.id.widget_recent_1, Color.parseColor("#E2E8F0"))
                    views.setTextColor(R.id.widget_recent_2, Color.parseColor("#94A3B8"))
                }
                WidgetTheme.LIGHT -> {
                    views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.bg_widget_light)
                    views.setTextColor(R.id.widget_title, Color.parseColor("#007791"))
                    views.setTextColor(R.id.widget_recent_1, Color.parseColor("#0F172A"))
                    views.setTextColor(R.id.widget_recent_2, Color.parseColor("#475569"))
                    views.setTextColor(R.id.widget_badge, Color.parseColor("#007791"))
                    views.setTextColor(R.id.btn_widget_voice, Color.parseColor("#007791"))
                    views.setTextColor(R.id.btn_widget_chat, Color.parseColor("#007791"))
                    views.setTextColor(R.id.btn_widget_recent, Color.parseColor("#007791"))
                }
                WidgetTheme.GLASS -> {
                    views.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.bg_widget_glass)
                    views.setTextColor(R.id.widget_title, Color.parseColor("#00E5FF"))
                    views.setTextColor(R.id.widget_recent_1, Color.parseColor("#F1F5F9"))
                    views.setTextColor(R.id.widget_recent_2, Color.parseColor("#BAE6FD"))
                }
            }
        }

        private fun applyLabels(
            views: RemoteViews,
            lang: AppLanguage,
            recentTitles: List<String>,
            config: WidgetConfig
        ) {
            val (voiceTxt, chatTxt, recentTxt, emptyTxt) = when (lang) {
                AppLanguage.TURKISH -> listOf("Sesli", "Sohbet", "Son İşlemler", "• Son işlemler burada görünür")
                AppLanguage.GERMAN -> listOf("Sprache", "Chat", "Letzte", "• Letzte Aktionen erscheinen hier")
                AppLanguage.FRENCH -> listOf("Voix", "Chat", "Récent", "• Actions récentes ici")
                AppLanguage.SPANISH -> listOf("Voz", "Chat", "Reciente", "• Acciones recientes aquí")
                AppLanguage.ARABIC -> listOf("صوت", "محادثة", "الأخيرة", "• الإجراءات الأخيرة هنا")
                else -> listOf("Voice", "Chat", "Recent", "• Recent actions appear here")
            }
            views.setTextViewText(R.id.btn_widget_voice, voiceTxt)
            views.setTextViewText(R.id.btn_widget_chat, chatTxt)
            views.setTextViewText(R.id.btn_widget_recent, recentTxt)

            if (!config.showRecentSummary) {
                views.setViewVisibility(R.id.widget_recent_1, View.GONE)
                views.setViewVisibility(R.id.widget_recent_2, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_recent_1, View.VISIBLE)
                val first = recentTitles.getOrNull(0)?.let { "• $it" } ?: emptyTxt
                val second = recentTitles.getOrNull(1)?.let { "• $it" } ?: ""
                views.setTextViewText(R.id.widget_recent_1, first)
                views.setTextViewText(R.id.widget_recent_2, second)
                views.setViewVisibility(
                    R.id.widget_recent_2,
                    if (second.isNotBlank()) View.VISIBLE else View.GONE
                )
            }
        }

        private fun buildLaunchPendingIntent(
            context: Context,
            uriString: String,
            mode: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(uriString)
                putExtra(EXTRA_LAUNCH_MODE, mode)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
