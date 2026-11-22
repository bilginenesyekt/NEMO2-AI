package com.example.data.ai

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.AiEngineMode
import com.example.data.model.ChatMessage
import com.example.data.model.ResponseTone
import com.example.data.model.UserPreferences
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface AiStreamEvent {
    data class Delta(
        val textChunk: String,
        val accumulatedText: String,
        val engineLabel: String,
        val ttftMs: Long
    ) : AiStreamEvent

    data class Completed(
        val fullText: String,
        val engineLabel: String,
        val ttftMs: Long,
        val totalMs: Long
    ) : AiStreamEvent
}

/**
 * Dual-infrastructure high-speed streaming AI Engine:
 * 1. OpenAI ChatGPT API (SSE streaming via pre-warmed HTTP/2 OkHttp pool) when OPENAI_API_KEY
 *    or a user-supplied key is configured.
 * 2. Built-in NEMO Cloud AI via Firebase AI Logic (gemini-3.5-flash-lite in Fast Mode,
 *    gemini-3.8-flash in Deep Mode) with zero-key App Check attestation.
 */
class NemoAiEngine {

    private val fastGeminiModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                temperature = 0.65f
                maxOutputTokens = 1024
            }
        )
    }

    private val deepGeminiModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.7f
                maxOutputTokens = 2048
            }
        )
    }

    fun hasOpenAiKey(customUserKey: String): Boolean {
        val effective = resolveEffectiveOpenAiKey(customUserKey)
        return effective.isNotBlank()
    }

    fun resolveEffectiveOpenAiKey(customUserKey: String): String {
        val userTrimmed = customUserKey.trim()
        if (userTrimmed.isNotEmpty()) return userTrimmed
        val buildKey = BuildConfig.OPENAI_API_KEY.trim()
        return if (buildKey.isNotEmpty() && buildKey != PLACEHOLDER_OPENAI_KEY) buildKey else ""
    }

    fun streamPersonalizedReply(
        prompt: String,
        history: List<ChatMessage>,
        prefs: UserPreferences,
        recentActionsSummary: String,
        customOpenAiKey: String
    ): Flow<AiStreamEvent> = flow {
        val openAiKey = resolveEffectiveOpenAiKey(customOpenAiKey)
        val useChatGpt = when (prefs.preferredEngine) {
            AiEngineMode.CHATGPT -> openAiKey.isNotBlank()
            AiEngineMode.NEMO_CLOUD -> false
            AiEngineMode.AUTO -> openAiKey.isNotBlank()
        }

        if (prefs.preferredEngine == AiEngineMode.CHATGPT && openAiKey.isBlank()) {
            throw IllegalStateException("ChatGPT API key is not configured. Add OPENAI_API_KEY in AI Studio Secrets or Settings.")
        }

        val systemPrompt = buildSystemPrompt(prefs, recentActionsSummary)
        val trimmedHistory = history.takeLast(MAX_CONTEXT_TURNS)

        if (useChatGpt) {
            val modelName = if (prefs.fastModeEnabled) "gpt-4o-mini" else "gpt-4o"
            val engineLabel = if (prefs.fastModeEnabled) "ChatGPT • Fast" else "ChatGPT • 4o"
            streamOpenAiChatCompletions(
                apiKey = openAiKey,
                modelName = modelName,
                engineLabel = engineLabel,
                systemPrompt = systemPrompt,
                history = trimmedHistory,
                userPrompt = prompt
            ).collect { emit(it) }
        } else {
            val engineLabel = if (prefs.fastModeEnabled) "NEMO Flash ⚡" else "NEMO Deep"
            streamFirebaseAiLogic(
                fastMode = prefs.fastModeEnabled,
                engineLabel = engineLabel,
                systemPrompt = systemPrompt,
                history = trimmedHistory,
                userPrompt = prompt
            ).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    private fun streamOpenAiChatCompletions(
        apiKey: String,
        modelName: String,
        engineLabel: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        userPrompt: String
    ): Flow<AiStreamEvent> = flow {
        val startNanos = System.nanoTime()
        var firstTokenMs = 0L
        val fullBuilder = StringBuilder()

        val messagesArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.forEach { msg ->
                val mappedRole = if (msg.role == "assistant") "assistant" else "user"
                put(JSONObject().put("role", mappedRole).put("content", msg.content))
            }
            put(JSONObject().put("role", "user").put("content", userPrompt))
        }

        val bodyJson = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("stream", true)
            put("temperature", 0.7)
        }.toString()

        val request = Request.Builder()
            .url(OPENAI_CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        withTimeout(AI_TIMEOUT_MS) {
            sharedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(300)
                    throw IllegalStateException("ChatGPT API HTTP ${response.code}: $errorBody")
                }
                val source = response.body?.source()
                    ?: throw IllegalStateException("Empty response stream from ChatGPT API")

                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) break

                    val deltaText = try {
                        val obj = JSONObject(data)
                        val choices = obj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            choices.getJSONObject(0)
                                .optJSONObject("delta")
                                ?.optString("content")
                                .orEmpty()
                        } else {
                            ""
                        }
                    } catch (_: Exception) {
                        ""
                    }

                    if (deltaText.isNotEmpty()) {
                        if (firstTokenMs == 0L) {
                            firstTokenMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                        }
                        fullBuilder.append(deltaText)
                        emit(
                            AiStreamEvent.Delta(
                                textChunk = deltaText,
                                accumulatedText = fullBuilder.toString(),
                                engineLabel = engineLabel,
                                ttftMs = firstTokenMs
                            )
                        )
                    }
                }
            }
        }

        val finalStr = fullBuilder.toString().trim()
        if (finalStr.isEmpty()) {
            throw IllegalStateException("ChatGPT returned an empty response.")
        }
        val totalMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
        emit(
            AiStreamEvent.Completed(
                fullText = finalStr,
                engineLabel = engineLabel,
                ttftMs = if (firstTokenMs > 0L) firstTokenMs else totalMs,
                totalMs = totalMs
            )
        )
    }

    private fun streamFirebaseAiLogic(
        fastMode: Boolean,
        engineLabel: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        userPrompt: String
    ): Flow<AiStreamEvent> = flow {
        val startNanos = System.nanoTime()
        var firstTokenMs = 0L
        val fullBuilder = StringBuilder()

        val model = if (fastMode) fastGeminiModel else deepGeminiModel
        val chatHistory = buildList {
            add(content("user") { text("[System Instructions]\n$systemPrompt") })
            add(content("model") { text("Understood. I am NEMO and I will follow these personalized preferences.") })
            history.forEach { msg ->
                val role = if (msg.role == "assistant") "model" else "user"
                add(content(role) { text(msg.content) })
            }
        }

        val chat = model.startChat(history = chatHistory)
        try {
            withTimeout(AI_TIMEOUT_MS) {
                chat.sendMessageStream(userPrompt).collect { chunk ->
                    currentCoroutineContext().ensureActive()
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        if (firstTokenMs == 0L) {
                            firstTokenMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
                        }
                        fullBuilder.append(chunkText)
                        emit(
                            AiStreamEvent.Delta(
                                textChunk = chunkText,
                                accumulatedText = fullBuilder.toString(),
                                engineLabel = engineLabel,
                                ttftMs = firstTokenMs
                            )
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Firebase AI Logic stream timed out", e)
            throw IllegalStateException("Response timed out after 60s. Please retry.", e)
        } catch (e: CancellationException) {
            throw e
        }

        val finalStr = fullBuilder.toString().trim()
        if (finalStr.isEmpty()) {
            throw IllegalStateException("NEMO returned an empty response.")
        }
        val totalMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)
        emit(
            AiStreamEvent.Completed(
                fullText = finalStr,
                engineLabel = engineLabel,
                ttftMs = if (firstTokenMs > 0L) firstTokenMs else totalMs,
                totalMs = totalMs
            )
        )
    }

    private fun buildSystemPrompt(prefs: UserPreferences, recentActionsSummary: String): String {
        val toneDirective = when (prefs.responseTone) {
            ResponseTone.FRIENDLY -> "Warm, helpful, conversational, and encouraging."
            ResponseTone.PROFESSIONAL -> "Clear, structured, executive-ready, and accurate."
            ResponseTone.CONCISE -> "Ultra-concise, bullet-driven, zero fluff."
            ResponseTone.CREATIVE -> "Insightful, expressive, offering fresh angles."
        }
        val speedDirective = if (prefs.fastModeEnabled) {
            "FAST RESPONSE MODE IS ACTIVE: prioritize immediate clarity, direct answers, and concise formatting."
        } else {
            "DEEP REASONING MODE IS ACTIVE: provide thorough, well-structured, step-by-step reasoning."
        }
        val personaLine = if (prefs.customPersona.isNotBlank()) {
            "User profile & instructions: ${prefs.customPersona}"
        } else {
            "User name: ${prefs.displayName.ifBlank { "User" }}"
        }
        val interestsLine = if (prefs.interests.isNotEmpty()) {
            "User interests: ${prefs.interests.joinToString(", ")}"
        } else ""
        val recentLine = if (recentActionsSummary.isNotBlank()) {
            "Recent user context:\n$recentActionsSummary"
        } else ""

        return buildString {
            appendLine("You are NEMO, an advanced personal AI assistant.")
            appendLine("Respond in ${prefs.language.nativeName} (${prefs.language.code}) unless the user explicitly asks in another language.")
            appendLine("Tone: $toneDirective")
            appendLine(speedDirective)
            appendLine(personaLine)
            if (interestsLine.isNotEmpty()) appendLine(interestsLine)
            if (recentLine.isNotEmpty()) appendLine(recentLine)
        }.trim()
    }

    companion object {
        private const val TAG = "NemoAiEngine"
        private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
        private const val PLACEHOLDER_OPENAI_KEY = "MY_OPENAI_API_KEY"
        private const val AI_TIMEOUT_MS = 60_000L
        private const val MAX_CONTEXT_TURNS = 10
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Pre-warmed HTTP/2 connection pool for minimal ChatGPT TTFB
        private val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
