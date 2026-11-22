package com.example.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.AiEngineMode
import com.example.data.model.AppLanguage
import com.example.data.model.ResponseTone
import com.example.data.model.ThemeMode
import com.example.data.model.UserPreferences
import com.example.data.model.WidgetConfig
import com.example.data.model.WidgetTheme
import com.example.i18n.NemoStrings
import com.example.widget.NemoWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsHubScreen(
    prefs: UserPreferences,
    widgetConfig: WidgetConfig,
    hasOpenAiKey: Boolean,
    customOpenAiKey: String,
    keyFingerprint: String,
    recoveryKey: String,
    onToggleFastMode: (Boolean) -> Unit,
    onToggleAutoSpeak: (Boolean) -> Unit,
    onSavePersonalization: (ResponseTone, String, AiEngineMode) -> Unit,
    onSaveCustomOpenAiKey: (String) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onBackupNow: () -> Unit,
    onRestoreFromCloud: () -> Unit,
    onConfigurePassphrase: (String) -> Unit,
    onUnlockPassphrase: (String) -> Unit,
    onUpdateWidgetConfig: (WidgetConfig) -> Unit,
    onSignOut: () -> Unit
) {
    val strings = NemoStrings.get(prefs.language)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var selectedTone by remember(prefs.responseTone) { mutableStateOf(prefs.responseTone) }
    var personaDraft by remember(prefs.customPersona) { mutableStateOf(prefs.customPersona) }
    var selectedEngine by remember(prefs.preferredEngine) { mutableStateOf(prefs.preferredEngine) }
    var openAiKeyDraft by remember(customOpenAiKey) { mutableStateOf(customOpenAiKey) }

    var showSetPassphraseDialog by remember { mutableStateOf(false) }
    var showUnlockPassphraseDialog by remember { mutableStateOf(false) }
    var passphraseInput by remember { mutableStateOf("") }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .widthIn(max = 760.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = strings.settingsTab,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // 1. Personalization & Speed
            SectionCard(
                icon = Icons.Rounded.AutoAwesome,
                title = strings.personalizationTitle
            ) {
                ToggleRow(strings.fastModeOn, prefs.fastModeEnabled, onToggleFastMode)
                ToggleRow(strings.autoSpeak, prefs.autoSpeakEnabled, onToggleAutoSpeak)

                Text(strings.responseToneLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResponseTone.entries.forEach { tone ->
                        FilterChip(
                            selected = selectedTone == tone,
                            onClick = { selectedTone = tone },
                            label = { Text(strings.toneNames[tone] ?: tone.name) }
                        )
                    }
                }

                OutlinedTextField(
                    value = personaDraft,
                    onValueChange = { personaDraft = it },
                    label = { Text(strings.customPersonaLabel) },
                    placeholder = { Text(strings.customPersonaHint) },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { onSavePersonalization(selectedTone, personaDraft, selectedEngine) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_personalization_button")
                ) {
                    Text(strings.savePersonalization)
                }
            }

            // 2. ChatGPT & AI Infrastructure
            SectionCard(
                icon = Icons.Rounded.Key,
                title = strings.aiEngineSectionTitle
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AiEngineMode.AUTO to strings.engineAuto,
                        AiEngineMode.CHATGPT to strings.engineChatGpt,
                        AiEngineMode.NEMO_CLOUD to strings.engineNemoCloud
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = selectedEngine == mode,
                            onClick = {
                                selectedEngine = mode
                                onSavePersonalization(selectedTone, personaDraft, mode)
                            },
                            label = { Text(label) }
                        )
                    }
                }

                Text(
                    text = if (hasOpenAiKey) strings.openAiConfiguredSecrets else strings.openAiNotConfiguredNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasOpenAiKey) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = openAiKeyDraft,
                    onValueChange = { openAiKeyDraft = it },
                    label = { Text(strings.openAiKeyLabel) },
                    placeholder = { Text(strings.openAiKeyHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = { onSaveCustomOpenAiKey(openAiKeyDraft) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save API Key")
                }
            }

            // 3. Cloud Backup & E2EE Security Vault
            SectionCard(
                icon = Icons.Rounded.Lock,
                title = strings.cloudBackupTitle
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = strings.encryptionActiveLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "${strings.keyFingerprintLabel}: $keyFingerprint",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            val backupLabel = prefs.lastBackupAtMillis?.let {
                                dateFormatter.format(Date(it))
                            } ?: strings.lastBackupNever
                            Text(
                                text = backupLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onBackupNow,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cloud_backup_now_button")
                    ) {
                        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.backupNow, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onRestoreFromCloud,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cloud_restore_button")
                    ) {
                        Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(strings.restoreFromCloud, maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            passphraseInput = ""
                            showSetPassphraseDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(strings.setPassphrase, maxLines = 1)
                    }
                    if (prefs.hasCloudE2eeVerifier) {
                        OutlinedButton(
                            onClick = {
                                passphraseInput = ""
                                showUnlockPassphraseDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(strings.unlockPassphrase, maxLines = 1)
                        }
                    }
                }

                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(recoveryKey)) }
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${strings.recoveryKeyLabel} (${strings.copyText})")
                }
            }

            // 4. Appearance & Multi-Language
            SectionCard(
                icon = Icons.Rounded.Palette,
                title = strings.appearanceTitle
            ) {
                Text(strings.themeLabel, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.themeMode == mode,
                            onClick = { onUpdateThemeMode(mode) },
                            label = { Text(strings.themeNames[mode] ?: mode.name) },
                            modifier = Modifier.testTag("theme_chip_${mode.name}")
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.languageLabel, style = MaterialTheme.typography.labelLarge)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = prefs.language == lang,
                            onClick = { onUpdateLanguage(lang) },
                            label = { Text(lang.nativeName) },
                            modifier = Modifier.testTag("lang_chip_${lang.code}")
                        )
                    }
                }
            }

            // 5. Customizable Home Widget Studio
            SectionCard(
                icon = Icons.Rounded.Widgets,
                title = strings.widgetStudioTitle
            ) {
                Text(
                    text = strings.widgetStudioSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Live Preview Card
                val previewBg = when (widgetConfig.theme) {
                    WidgetTheme.DARK -> Color(0xFF0D1526)
                    WidgetTheme.LIGHT -> Color(0xFFF4F8FB)
                    WidgetTheme.GLASS -> Color(0xFF0B3B52)
                }
                val previewFg = if (widgetConfig.theme == WidgetTheme.LIGHT) Color(0xFF007791) else Color(0xFF00E5FF)
                val previewSub = if (widgetConfig.theme == WidgetTheme.LIGHT) Color(0xFF334155) else Color(0xFFE2E8F0)

                Surface(
                    color = previewBg,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, previewFg.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("NEMO AI", style = MaterialTheme.typography.titleSmall, color = previewFg)
                            Text("E2EE", style = MaterialTheme.typography.labelSmall, color = previewFg)
                        }
                        if (widgetConfig.showRecentSummary) {
                            Text(
                                text = "• ${strings.recentActionsTitle}",
                                style = MaterialTheme.typography.bodySmall,
                                color = previewSub
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (widgetConfig.showVoiceButton) PreviewPill(strings.voiceCommandHint, previewFg)
                            if (widgetConfig.showQuickChatButton) PreviewPill(strings.chatTab, previewFg)
                            if (widgetConfig.showRecentActionsButton) PreviewPill(strings.recentActionsTitle, previewFg)
                        }
                    }
                }

                Text(strings.widgetThemeLabel, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetTheme.entries.forEach { wTheme ->
                        FilterChip(
                            selected = widgetConfig.theme == wTheme,
                            onClick = { onUpdateWidgetConfig(widgetConfig.copy(theme = wTheme)) },
                            label = { Text(wTheme.name) }
                        )
                    }
                }

                ToggleRow(strings.recentActionsTitle, widgetConfig.showRecentSummary) {
                    onUpdateWidgetConfig(widgetConfig.copy(showRecentSummary = it))
                }
                ToggleRow(strings.voiceCommandHint, widgetConfig.showVoiceButton) {
                    onUpdateWidgetConfig(widgetConfig.copy(showVoiceButton = it))
                }

                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val manager = AppWidgetManager.getInstance(context)
                            val provider = ComponentName(context, NemoWidgetProvider::class.java)
                            if (manager.isRequestPinAppWidgetSupported) {
                                manager.requestPinAppWidget(provider, null, null)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_widget_button")
                ) {
                    Text(strings.pinWidgetButton)
                }
            }

            // 6. Voice Commands Guide
            SectionCard(
                icon = Icons.Rounded.GraphicEq,
                title = strings.voiceExamplesTitle
            ) {
                strings.voiceExamples.forEach { example ->
                    Text(
                        text = "• $example",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 7. Sign Out
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.signOut)
            }

            Text(
                text = "Enes Yekta Bilgin tarafından yapılmıştır",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp, bottom = 18.dp)
                    .testTag("creator_attribution_hub")
            )
        }
    }

    if (showSetPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { showSetPassphraseDialog = false },
            title = { Text(strings.setPassphrase) },
            text = {
                OutlinedTextField(
                    value = passphraseInput,
                    onValueChange = { passphraseInput = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    placeholder = { Text("Min 6 chars") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfigurePassphrase(passphraseInput)
                        showSetPassphraseDialog = false
                    },
                    enabled = passphraseInput.length >= 6
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetPassphraseDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }

    if (showUnlockPassphraseDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockPassphraseDialog = false },
            title = { Text(strings.unlockPassphrase) },
            text = {
                OutlinedTextField(
                    value = passphraseInput,
                    onValueChange = { passphraseInput = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onUnlockPassphrase(passphraseInput)
                        showUnlockPassphraseDialog = false
                    },
                    enabled = passphraseInput.isNotBlank()
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockPassphraseDialog = false }) {
                    Text(strings.close)
                }
            }
        )
    }
}

@Composable
private fun SectionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PreviewPill(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
