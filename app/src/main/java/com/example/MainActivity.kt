package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.crypto.E2eeCryptoManager
import com.example.data.ai.NemoAiEngine
import com.example.data.firebase.NemoFirestoreRepository
import com.example.data.firebase.attemptAutoSignIn
import com.example.data.firebase.authStateFlow
import com.example.data.firebase.configureAppCheck
import com.example.data.firebase.signOutUser
import com.example.data.local.PreferencesStore
import com.example.data.model.AppLanguage
import com.example.data.model.ThemeMode
import com.example.data.model.UiState
import com.example.data.model.UserPreferences
import com.example.i18n.NemoStrings
import com.example.ui.MainTab
import com.example.ui.NemoViewModel
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.SettingsHubScreen
import com.example.ui.theme.NemoTheme
import com.example.voice.NemoVoiceController
import com.example.widget.NemoWidgetProvider
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var launchDirectiveState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Mandatory App Check configuration with launch intent BEFORE UI/ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        launchDirectiveState = extractLaunchDirective(intent)

        setContent {
            val context = LocalContext.current
            val crypto = remember { E2eeCryptoManager(context.applicationContext) }
            val prefsStore = remember { PreferencesStore(context.applicationContext, crypto) }
            val localTheme by prefsStore.themeModeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val localLang by prefsStore.languageFlow.collectAsStateWithLifecycle(initialValue = AppLanguage.TURKISH)
            val scope = rememberCoroutineScope()

            NemoAppRoot(
                localTheme = localTheme,
                localLanguage = localLang,
                launchDirective = launchDirectiveState,
                onConsumeLaunchDirective = { launchDirectiveState = null },
                onLocalLanguageSelected = { lang ->
                    scope.launch { prefsStore.setLanguage(lang) }
                },
                crypto = crypto,
                prefsStore = prefsStore
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchDirectiveState = extractLaunchDirective(intent)
    }

    private fun extractLaunchDirective(intent: Intent?): String? {
        if (intent == null) return "recent"
        val extraMode = intent.getStringExtra(NemoWidgetProvider.EXTRA_LAUNCH_MODE)
        if (!extraMode.isNullOrBlank()) return extraMode

        val dataHost = intent.data?.host
        if (!dataHost.isNullOrBlank()) return dataHost

        val featureParam = intent.getStringExtra("feature")
        if (!featureParam.isNullOrBlank()) {
            return if (featureParam.contains("voice", ignoreCase = true)) "voice" else "recent"
        }

        return when (intent.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> "recent"
            else -> "recent"
        }
    }
}

@Composable
fun NemoAppRoot(
    localTheme: ThemeMode,
    localLanguage: AppLanguage,
    launchDirective: String?,
    onConsumeLaunchDirective: () -> Unit,
    onLocalLanguageSelected: (AppLanguage) -> Unit,
    crypto: E2eeCryptoManager,
    prefsStore: PreferencesStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            val cm = CredentialManager.create(context)
            attemptAutoSignIn(
                context = context,
                credentialManager = cm,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        val dir = if (localLanguage.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides dir) {
            NemoTheme(themeMode = localTheme) {
                AuthScreen(
                    selectedLanguage = localLanguage,
                    onLanguageSelected = onLocalLanguageSelected,
                    onAuthSuccess = {}
                )
            }
        }
    } else {
        val uid = user.uid
        key(uid) {
            val viewModel: NemoViewModel = viewModel(
                key = uid,
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val repo = NemoFirestoreRepository(app, crypto)
                        val aiEngine = NemoAiEngine()
                        val voice = NemoVoiceController(app)
                        NemoViewModel(
                            repository = repo,
                            aiEngine = aiEngine,
                            crypto = crypto,
                            prefsStore = prefsStore,
                            voiceController = voice,
                            currentUserId = uid,
                            onWidgetRefresh = { NemoWidgetProvider.refreshAllWidgets(app) },
                            onLaunchExternalApp = { query ->
                                com.example.launcher.AppLauncherHelper.launchAppByQuery(app, query)
                            }
                        )
                    }
                }
            )
            AuthenticatedNemoShell(
                viewModel = viewModel,
                fallbackTheme = localTheme,
                fallbackLang = localLanguage,
                launchDirective = launchDirective,
                onConsumeLaunchDirective = onConsumeLaunchDirective,
                onSignOut = {
                    val cm = CredentialManager.create(context)
                    signOutUser(cm, scope)
                }
            )
        }
    }
}

@Composable
private fun AuthenticatedNemoShell(
    viewModel: NemoViewModel,
    fallbackTheme: ThemeMode,
    fallbackLang: AppLanguage,
    launchDirective: String?,
    onConsumeLaunchDirective: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val prefsState by viewModel.userPrefsState.collectAsStateWithLifecycle()
    val prefs = (prefsState as? UiState.Success)?.data ?: UserPreferences(
        language = fallbackLang,
        themeMode = fallbackTheme
    )

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val recentActions by viewModel.recentActions.collectAsStateWithLifecycle()
    val activeMessages by viewModel.activeMessages.collectAsStateWithLifecycle()
    val streamingMessage by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val showRecentOnLaunch by viewModel.showRecentOnLaunch.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val widgetConfig by viewModel.widgetConfig.collectAsStateWithLifecycle()
    val customOpenAiKey by viewModel.customOpenAiKey.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val isListening by viewModel.voiceController.isListening.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.voiceController.partialTranscript.collectAsStateWithLifecycle()
    val rmsLevel by viewModel.voiceController.rmsLevel.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.voiceController.isSpeaking.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceController.voiceError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(launchDirective) {
        if (!launchDirective.isNullOrBlank()) {
            viewModel.handleLaunchDirective(launchDirective)
            if (launchDirective == "voice") {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    viewModel.voiceController.startListening(prefs.language) { transcript ->
                        viewModel.handleVoiceTranscript(transcript)
                    }
                }
            }
            onConsumeLaunchDirective()
        }
    }

    LaunchedEffect(statusBanner, voiceError) {
        val msg = statusBanner ?: voiceError
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    // BackHandler returns to Chat tab from secondary tabs
    BackHandler(enabled = selectedTab != MainTab.CHAT) {
        viewModel.selectTab(MainTab.CHAT)
    }

    val strings = NemoStrings.get(prefs.language)
    val layoutDir = if (prefs.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        NemoTheme(themeMode = prefs.themeMode) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 600.dp

                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!useNavigationRail) {
                            NavigationBar {
                                MainTab.entries.forEach { tab ->
                                    val selected = selectedTab == tab
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(tab) },
                                        icon = {
                                            Icon(
                                                imageVector = tabIcon(tab, selected),
                                                contentDescription = tabLabel(tab, strings)
                                            )
                                        },
                                        label = { Text(tabLabel(tab, strings)) },
                                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useNavigationRail) {
                            NavigationRail {
                                MainTab.entries.forEach { tab ->
                                    val selected = selectedTab == tab
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(tab) },
                                        icon = {
                                            Icon(
                                                imageVector = tabIcon(tab, selected),
                                                contentDescription = tabLabel(tab, strings)
                                            )
                                        },
                                        label = { Text(tabLabel(tab, strings)) },
                                        modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}")
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            when (selectedTab) {
                                MainTab.CHAT -> {
                                    ChatScreen(
                                        prefs = prefs,
                                        messages = activeMessages,
                                        streamingMessage = streamingMessage,
                                        isGenerating = isGenerating,
                                        recentActions = recentActions,
                                        showRecentOverlay = showRecentOnLaunch,
                                        isListening = isListening,
                                        partialTranscript = partialTranscript,
                                        rmsLevel = rmsLevel,
                                        isSpeaking = isSpeaking,
                                        hasOpenAiConfigured = viewModel.hasOpenAiConfigured(),
                                        onDismissRecentOverlay = viewModel::dismissRecentOverlay,
                                        onOpenRecentOverlay = viewModel::openRecentOverlay,
                                        onToggleFastMode = viewModel::toggleFastMode,
                                        onNewChat = viewModel::startNewChat,
                                        onSendMessage = { viewModel.sendMessage(it, voiceInitiated = false) },
                                        onStopGeneration = viewModel::stopGeneration,
                                        onStartVoice = {
                                            viewModel.voiceController.startListening(prefs.language) { transcript ->
                                                viewModel.handleVoiceTranscript(transcript)
                                            }
                                        },
                                        onStopVoice = { viewModel.voiceController.stopListening() },
                                        onSpeakText = { text -> viewModel.voiceController.speak(text, prefs.language) },
                                        onStopSpeaking = { viewModel.voiceController.stopSpeaking() },
                                        onOpenConversation = viewModel::openConversation,
                                        onOpenHistoryTab = { viewModel.selectTab(MainTab.HISTORY) }
                                    )
                                }
                                MainTab.HISTORY -> {
                                    HistoryScreen(
                                        language = prefs.language,
                                        conversationsState = conversationsState,
                                        recentActions = recentActions,
                                        searchQuery = searchQuery,
                                        onSearchQueryChange = viewModel::updateSearchQuery,
                                        onOpenConversation = viewModel::openConversation,
                                        onTogglePin = viewModel::togglePinConversation,
                                        onDeleteConversation = viewModel::deleteConversation,
                                        onNewChat = viewModel::startNewChat
                                    )
                                }
                                MainTab.HUB -> {
                                    SettingsHubScreen(
                                        prefs = prefs,
                                        widgetConfig = widgetConfig,
                                        hasOpenAiKey = viewModel.hasOpenAiConfigured(),
                                        customOpenAiKey = customOpenAiKey,
                                        keyFingerprint = viewModel.getKeyFingerprint(),
                                        recoveryKey = viewModel.exportRecoveryKey(),
                                        onToggleFastMode = viewModel::toggleFastMode,
                                        onToggleAutoSpeak = viewModel::toggleAutoSpeak,
                                        onSavePersonalization = viewModel::updatePersonalization,
                                        onSaveCustomOpenAiKey = viewModel::saveCustomOpenAiKey,
                                        onUpdateThemeMode = viewModel::updateThemeMode,
                                        onUpdateLanguage = viewModel::updateLanguage,
                                        onBackupNow = viewModel::performCloudBackup,
                                        onRestoreFromCloud = viewModel::restoreFromCloud,
                                        onConfigurePassphrase = viewModel::configureE2eePassphrase,
                                        onUnlockPassphrase = viewModel::unlockE2eePassphrase,
                                        onUpdateWidgetConfig = viewModel::updateWidgetConfig,
                                        onSignOut = onSignOut
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: MainTab, selected: Boolean) = when (tab) {
    MainTab.CHAT -> if (selected) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome
    MainTab.HISTORY -> if (selected) Icons.Rounded.History else Icons.Outlined.History
    MainTab.HUB -> if (selected) Icons.Rounded.Tune else Icons.Outlined.Tune
}

private fun tabLabel(tab: MainTab, strings: com.example.i18n.NemoLocalizedStrings) = when (tab) {
    MainTab.CHAT -> strings.chatTab
    MainTab.HISTORY -> strings.historyTab
    MainTab.HUB -> strings.settingsTab
}
