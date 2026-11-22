package com.example.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.crypto.E2eeCryptoManager
import com.example.data.local.PreferencesStore
import com.example.data.model.ThemeMode
import com.example.data.model.WidgetConfig
import com.example.data.model.WidgetTheme
import com.example.ui.theme.NemoTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class NemoWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val store = PreferencesStore(this, E2eeCryptoManager(this))
        val initialConfig = runBlocking { store.getWidgetConfigSnapshot() }
        val appTheme = runBlocking { store.getThemeModeSnapshot() }

        setContent {
            NemoTheme(themeMode = appTheme) {
                var config by remember { mutableStateOf(initialConfig) }
                val scope = rememberCoroutineScope()

                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("NEMO Widget") })
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Widget Görünümü",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WidgetTheme.entries.forEach { theme ->
                                FilterChip(
                                    selected = config.theme == theme,
                                    onClick = { config = config.copy(theme = theme) },
                                    label = { Text(theme.name) }
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ToggleRow("Son İşlemler Özeti", config.showRecentSummary) {
                                    config = config.copy(showRecentSummary = it)
                                }
                                ToggleRow("Sesli Komut Butonu", config.showVoiceButton) {
                                    config = config.copy(showVoiceButton = it)
                                }
                                ToggleRow("Hızlı Sohbet Butonu", config.showQuickChatButton) {
                                    config = config.copy(showQuickChatButton = it)
                                }
                                ToggleRow("Son İşlemler Butonu", config.showRecentActionsButton) {
                                    config = config.copy(showRecentActionsButton = it)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Button(
                            onClick = {
                                scope.launch {
                                    store.setWidgetConfig(config)
                                    val manager = AppWidgetManager.getInstance(this@NemoWidgetConfigActivity)
                                    if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                                        NemoWidgetProvider.updateAppWidget(
                                            this@NemoWidgetConfigActivity,
                                            manager,
                                            appWidgetId
                                        )
                                        val result = Intent().putExtra(
                                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                                            appWidgetId
                                        )
                                        setResult(Activity.RESULT_OK, result)
                                    } else {
                                        NemoWidgetProvider.refreshAllWidgets(this@NemoWidgetConfigActivity)
                                    }
                                    finish()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("save_widget_config_button")
                        ) {
                            Text("Widget'ı Kaydet")
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
