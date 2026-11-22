package com.example.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.crypto.E2eeCryptoManager
import com.example.data.ai.AiStreamEvent
import com.example.data.ai.NemoAiEngine
import com.example.data.firebase.NemoFirestoreRepository
import com.example.data.local.PreferencesStore
import com.example.data.model.ActionType
import com.example.data.model.AiEngineMode
import com.example.data.model.AppLanguage
import com.example.data.model.ChatConversation
import com.example.data.model.ChatMessage
import com.example.data.model.RecentActionItem
import com.example.data.model.ResponseTone
import com.example.data.model.ThemeMode
import com.example.data.model.UiState
import com.example.data.model.UserPreferences
import com.example.data.model.WidgetConfig
import com.example.i18n.NemoStrings
import com.example.launcher.AppLaunchResult
import com.example.voice.NemoVoiceController
import com.example.voice.VoiceCommandRouter
import com.example.voice.VoiceIntent
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainTab { CHAT, HISTORY, HUB }

class NemoViewModel(
    private val repository: NemoFirestoreRepository,
    private val aiEngine: NemoAiEngine,
    private val crypto: E2eeCryptoManager,
    private val prefsStore: PreferencesStore,
    val voiceController: NemoVoiceController,
    private val currentUserId: String,
    private val onWidgetRefresh: () -> Unit,
    private val onLaunchExternalApp: (String) -> AppLaunchResult
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(MainTab.CHAT)
    val selectedTab: StateFlow<MainTab> = _selectedTab.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _showRecentOnLaunch = MutableStateFlow(true)
    val showRecentOnLaunch: StateFlow<Boolean> = _showRecentOnLaunch.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var activeGenerationJob: Job? = null

    val userPrefsState: StateFlow<UiState<UserPreferences>> = repository.observeUserPreferences(currentUserId)
        .map<UserPreferences, UiState<UserPreferences>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Error observing user preferences", root)
            emit(UiState.Error(root.message ?: "Failed to load preferences"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    val conversationsState: StateFlow<UiState<List<ChatConversation>>> = repository.observeConversations(currentUserId)
        .map<List<ChatConversation>, UiState<List<ChatConversation>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val root = firestoreError ?: error
            Log.w(TAG, "Error observing conversations", root)
            emit(UiState.Error(root.message ?: "Failed to load conversations"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UiState.Loading
        )

    private val cloudRecentActions: StateFlow<List<RecentActionItem>> = repository.observeRecentActions(currentUserId)
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing recent actions", firestoreError ?: error)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    // Instant cached + live cloud recent actions (top 5)
    val recentActions: StateFlow<List<RecentActionItem>> = combine(
        prefsStore.cachedRecentActionsFlow,
        cloudRecentActions
    ) { cached, cloud ->
        val merged = if (cloud.isNotEmpty()) cloud else cached
        merged.take(5)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeMessages: StateFlow<List<ChatMessage>> = _activeConversationId
        .flatMapLatest { convId ->
            if (convId.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeMessages(currentUserId, convId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        Log.w(TAG, "Error observing messages", firestoreError ?: error)
                        emit(emptyList())
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val widgetConfig: StateFlow<WidgetConfig> = prefsStore.widgetConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), WidgetConfig())

    val customOpenAiKey: StateFlow<String> = prefsStore.customOpenAiKeyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    init {
        viewModelScope.launch {
            val lang = prefsStore.getLanguageSnapshot()
            val theme = prefsStore.getThemeModeSnapshot()
            repository.ensureUserProfile(lang, theme)
        }
        // Keep local encrypted cache + home widget synced with cloud recent actions
        viewModelScope.launch {
            cloudRecentActions.collect { items ->
                if (items.isNotEmpty()) {
                    prefsStore.cacheRecentActions(items.take(5))
                    onWidgetRefresh()
                }
            }
        }
    }

    fun currentPreferences(): UserPreferences {
        return (userPrefsState.value as? UiState.Success)?.data ?: UserPreferences()
    }

    fun hasOpenAiConfigured(): Boolean = aiEngine.hasOpenAiKey(customOpenAiKey.value)

    fun getKeyFingerprint(): String = crypto.getKeyFingerprint()

    fun exportRecoveryKey(): String = crypto.exportRecoveryKeyHex()

    fun selectTab(tab: MainTab) {
        _selectedTab.value = tab
    }

    fun dismissRecentOverlay() {
        _showRecentOnLaunch.value = false
    }

    fun openRecentOverlay() {
        _showRecentOnLaunch.value = true
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun startNewChat() {
        activeGenerationJob?.cancel()
        _isGenerating.value = false
        _streamingMessage.value = null
        _activeConversationId.value = null
        _selectedTab.value = MainTab.CHAT
        _showRecentOnLaunch.value = false
    }

    fun openConversation(conversationId: String) {
        activeGenerationJob?.cancel()
        _isGenerating.value = false
        _streamingMessage.value = null
        _activeConversationId.value = conversationId
        _selectedTab.value = MainTab.CHAT
        _showRecentOnLaunch.value = false
    }

    fun handleLaunchDirective(mode: String?) {
        when (mode) {
            "recent" -> {
                _selectedTab.value = MainTab.CHAT
                _showRecentOnLaunch.value = true
            }
            "voice" -> {
                _selectedTab.value = MainTab.CHAT
                _showRecentOnLaunch.value = false
            }
            "chat" -> {
                _selectedTab.value = MainTab.CHAT
                _showRecentOnLaunch.value = false
            }
        }
    }

    fun sendMessage(rawText: String, voiceInitiated: Boolean = false) {
        val prompt = rawText.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        if (!voiceInitiated) {
            val typedIntent = VoiceCommandRouter.parse(prompt)
            if (typedIntent is VoiceIntent.OpenExternalApp || typedIntent is VoiceIntent.ShowRecentActions) {
                handleVoiceTranscript(prompt)
                return
            }
        }

        val prefs = currentPreferences()
        val historySnapshot = activeMessages.value
        val recentDigest = recentActions.value.take(3)
            .joinToString("\n") { "- ${it.title}: ${it.detail}" }

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            _isGenerating.value = true
            _showRecentOnLaunch.value = false

            var convId = _activeConversationId.value
            val isNewConv = convId.isNullOrBlank()
            if (isNewConv) {
                val created = repository.createConversation(
                    title = prompt.take(64),
                    preview = prompt.take(120),
                    languageCode = prefs.language.code,
                    engineUsed = prefs.preferredEngine.name
                )
                convId = created.getOrNull()
                if (convId == null) {
                    _isGenerating.value = false
                    _statusBanner.value = created.exceptionOrNull()?.localizedMessage ?: "Could not create encrypted conversation."
                    return@launch
                }
                _activeConversationId.value = convId
            }

            // Persist user message immediately (encrypted)
            val userMsgRes = repository.addEncryptedMessage(
                conversationId = convId,
                role = "user",
                plainContent = prompt,
                engineUsed = "USER",
                latencyMs = 0L,
                voiceInitiated = voiceInitiated
            )
            if (userMsgRes.isFailure) {
                _isGenerating.value = false
                _statusBanner.value = userMsgRes.exceptionOrNull()?.localizedMessage ?: "Failed to save message."
                return@launch
            }

            // Placeholder streaming bubble
            _streamingMessage.value = ChatMessage(
                id = "streaming_temp",
                conversationId = convId,
                role = "assistant",
                content = "",
                engineUsed = if (prefs.fastModeEnabled) "Fast ⚡" else "Deep",
                latencyMs = 0L,
                ttftMs = 0L,
                isStreaming = true
            )

            try {
                var finalEvent: AiStreamEvent.Completed? = null
                aiEngine.streamPersonalizedReply(
                    prompt = prompt,
                    history = historySnapshot,
                    prefs = prefs,
                    recentActionsSummary = recentDigest,
                    customOpenAiKey = customOpenAiKey.value
                ).collect { event ->
                    when (event) {
                        is AiStreamEvent.Delta -> {
                            _streamingMessage.value = ChatMessage(
                                id = "streaming_temp",
                                conversationId = convId,
                                role = "assistant",
                                content = event.accumulatedText,
                                engineUsed = event.engineLabel,
                                latencyMs = event.ttftMs,
                                ttftMs = event.ttftMs,
                                isStreaming = true
                            )
                        }
                        is AiStreamEvent.Completed -> {
                            finalEvent = event
                        }
                    }
                }

                val completed = finalEvent
                if (completed != null) {
                    _streamingMessage.value = null
                    _isGenerating.value = false

                    if (prefs.autoSpeakEnabled) {
                        voiceController.speak(completed.fullText, prefs.language)
                    }

                    // Async encrypted Firestore persistence & recent action logging off the streaming hot path
                    launch {
                        repository.addEncryptedMessage(
                            conversationId = convId,
                            role = "assistant",
                            plainContent = completed.fullText,
                            engineUsed = completed.engineLabel,
                            latencyMs = completed.totalMs,
                            voiceInitiated = false
                        )
                        val title = historySnapshot.firstOrNull { it.role == "user" }?.content?.take(64)
                            ?: prompt.take(64)
                        val totalCount = historySnapshot.size + 2
                        repository.updateConversationMeta(
                            conversationId = convId,
                            title = title,
                            preview = completed.fullText.take(120),
                            languageCode = prefs.language.code,
                            engineUsed = completed.engineLabel,
                            messageCount = totalCount
                        )
                        recordAction(
                            type = if (voiceInitiated) ActionType.VOICE_COMMAND else ActionType.CHAT,
                            title = title,
                            detail = completed.fullText.take(90),
                            targetConversationId = convId
                        )
                    }
                } else {
                    _streamingMessage.value = null
                    _isGenerating.value = false
                }
            } catch (e: CancellationException) {
                _streamingMessage.value = null
                _isGenerating.value = false
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI generation failed", e)
                _streamingMessage.value = null
                _isGenerating.value = false
                _statusBanner.value = e.localizedMessage ?: "AI response failed."
            }
        }
    }

    fun stopGeneration() {
        activeGenerationJob?.cancel()
        _streamingMessage.value = null
        _isGenerating.value = false
    }

    fun handleVoiceTranscript(transcript: String) {
        val intent = VoiceCommandRouter.parse(transcript)
        val prefs = currentPreferences()
        val strings = NemoStrings.get(prefs.language)

        when (intent) {
            VoiceIntent.ShowRecentActions -> {
                _selectedTab.value = MainTab.CHAT
                _showRecentOnLaunch.value = true
                viewModelScope.launch {
                    recordAction(ActionType.VOICE_COMMAND, strings.recentActionsTitle, transcript)
                }
            }
            VoiceIntent.NewChat -> {
                startNewChat()
                viewModelScope.launch {
                    recordAction(ActionType.VOICE_COMMAND, strings.newChat, transcript)
                }
            }
            is VoiceIntent.SetTheme -> {
                updateThemeMode(intent.mode)
            }
            is VoiceIntent.ToggleFastMode -> {
                toggleFastMode(intent.enabled)
            }
            VoiceIntent.BackupNow -> {
                _selectedTab.value = MainTab.HUB
                performCloudBackup()
            }
            VoiceIntent.OpenSettings -> {
                _selectedTab.value = MainTab.HUB
            }
            VoiceIntent.OpenHistory -> {
                _selectedTab.value = MainTab.HISTORY
            }
            is VoiceIntent.OpenExternalApp -> {
                launchExternalApp(intent.appQuery, transcript, prefs.autoSpeakEnabled, prefs.language, strings)
            }
            is VoiceIntent.SendChatPrompt -> {
                sendMessage(intent.prompt, voiceInitiated = true)
            }
        }
    }

    private fun launchExternalApp(
        appQuery: String,
        rawCommand: String,
        autoSpeak: Boolean,
        language: AppLanguage,
        strings: com.example.i18n.NemoLocalizedStrings
    ) {
        when (val result = onLaunchExternalApp(appQuery)) {
            is AppLaunchResult.Launched -> {
                val banner = "${strings.openingAppPrefix}: ${result.appLabel}"
                _statusBanner.value = banner
                if (autoSpeak) {
                    voiceController.speak(banner, language)
                }
                viewModelScope.launch {
                    recordAction(ActionType.VOICE_COMMAND, "${strings.openingAppPrefix}: ${result.appLabel}", rawCommand)
                }
            }
            is AppLaunchResult.NotFound -> {
                val banner = "${strings.appNotFoundPrefix}: ${result.query}"
                _statusBanner.value = banner
                if (autoSpeak) {
                    voiceController.speak(banner, language)
                }
            }
            is AppLaunchResult.Failed -> {
                _statusBanner.value = "${result.query}: ${result.reason}"
            }
        }
    }

    fun toggleFastMode(enabled: Boolean) {
        val updated = currentPreferences().copy(fastModeEnabled = enabled)
        viewModelScope.launch {
            prefsStore.setFastMode(enabled)
            repository.updatePreferences(updated)
        }
    }

    fun toggleAutoSpeak(enabled: Boolean) {
        val updated = currentPreferences().copy(autoSpeakEnabled = enabled)
        viewModelScope.launch {
            prefsStore.setAutoSpeak(enabled)
            repository.updatePreferences(updated)
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        val updated = currentPreferences().copy(themeMode = mode)
        viewModelScope.launch {
            prefsStore.setThemeMode(mode)
            repository.updatePreferences(updated)
        }
    }

    fun updateLanguage(language: AppLanguage) {
        val updated = currentPreferences().copy(language = language)
        viewModelScope.launch {
            prefsStore.setLanguage(language)
            repository.updatePreferences(updated)
            recordAction(ActionType.LANGUAGE, language.nativeName, "Language switched")
        }
    }

    fun updatePersonalization(
        tone: ResponseTone,
        customPersona: String,
        engineMode: AiEngineMode
    ) {
        val updated = currentPreferences().copy(
            responseTone = tone,
            customPersona = customPersona.trim(),
            preferredEngine = engineMode
        )
        viewModelScope.launch {
            prefsStore.setEngineMode(engineMode)
            val res = repository.updatePreferences(updated)
            if (res.isSuccess) {
                recordAction(ActionType.PERSONALIZATION, tone.name, customPersona.take(60))
                _statusBanner.value = "✓"
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to save preferences"
            }
        }
    }

    fun saveCustomOpenAiKey(key: String) {
        viewModelScope.launch {
            prefsStore.saveCustomOpenAiKey(key)
            _statusBanner.value = "OpenAI API Key updated"
        }
    }

    fun togglePinConversation(conv: ChatConversation) {
        viewModelScope.launch {
            repository.togglePinConversation(conv.id, !conv.pinned, conv.archived)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            if (_activeConversationId.value == conversationId) {
                _activeConversationId.value = null
            }
            repository.deleteConversation(conversationId)
        }
    }

    fun performCloudBackup() {
        viewModelScope.launch {
            val res = repository.performCloudBackupSync()
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                recordAction(ActionType.BACKUP, "Cloud E2EE Backup", "$count encrypted chats")
                _statusBanner.value = "E2EE Cloud Backup OK ($count)"
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Backup failed"
            }
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            val res = repository.restoreCloudSnapshotCount()
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                _statusBanner.value = "Restored $count encrypted chats"
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Restore failed"
            }
        }
    }

    fun configureE2eePassphrase(passphrase: String) {
        viewModelScope.launch {
            try {
                val (salt, verifier, iv) = crypto.configurePassphraseKey(passphrase)
                val res = repository.saveE2eeVerifier(salt, verifier, iv)
                if (res.isSuccess) {
                    _statusBanner.value = "E2EE Recovery Passphrase Active"
                } else {
                    _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to save passphrase"
                }
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage ?: "Invalid passphrase"
            }
        }
    }

    fun unlockE2eePassphrase(passphrase: String) {
        viewModelScope.launch {
            val verifierRes = repository.fetchE2eeVerifier()
            val tuple = verifierRes.getOrNull()
            if (tuple == null) {
                _statusBanner.value = "No cloud E2EE passphrase verifier found."
                return@launch
            }
            val ok = crypto.unlockWithPassphrase(passphrase, tuple.first, tuple.second, tuple.third)
            _statusBanner.value = if (ok) "E2EE Vault Unlocked" else "Incorrect passphrase"
        }
    }

    fun updateWidgetConfig(config: WidgetConfig) {
        viewModelScope.launch {
            prefsStore.setWidgetConfig(config)
            onWidgetRefresh()
            recordAction(ActionType.WIDGET, "Widget Updated", config.theme.name)
        }
    }

    private suspend fun recordAction(
        type: ActionType,
        title: String,
        detail: String,
        targetConversationId: String? = null
    ) {
        val localItem = RecentActionItem(
            id = "local_${System.currentTimeMillis()}",
            actionType = type,
            title = title,
            detail = detail,
            targetConversationId = targetConversationId,
            createdAtMillis = System.currentTimeMillis()
        )
        val mergedLocal = (listOf(localItem) + recentActions.value).take(5)
        prefsStore.cacheRecentActions(mergedLocal)
        onWidgetRefresh()
        repository.logRecentAction(type, title, detail, targetConversationId)
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.release()
    }

    companion object {
        private const val TAG = "NemoVM"
    }
}
