package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.crypto.E2eeCryptoManager
import com.example.data.model.ActionType
import com.example.data.model.AiEngineMode
import com.example.data.model.AppLanguage
import com.example.data.model.ChatConversation
import com.example.data.model.ChatMessage
import com.example.data.model.ConversationDoc
import com.example.data.model.EncryptedMessageDoc
import com.example.data.model.RecentActionDoc
import com.example.data.model.RecentActionItem
import com.example.data.model.ResponseTone
import com.example.data.model.ThemeMode
import com.example.data.model.UserPreferences
import com.example.data.model.UserProfileDoc
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class NemoFirestoreRepository(
    private val db: FirebaseFirestore,
    private val crypto: E2eeCryptoManager,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context, crypto: E2eeCryptoManager) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        crypto = crypto
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun userDocRef(uid: String) = db.collection("users").document(uid)
    private fun conversationsCol(uid: String) = userDocRef(uid).collection("conversations")
    private fun messagesCol(uid: String, conversationId: String) =
        conversationsCol(uid).document(conversationId).collection("messages")
    private fun recentActionsCol(uid: String) = userDocRef(uid).collection("recentActions")

    fun observeUserPreferences(userId: String): Flow<UserPreferences> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch" }
        val path = "users/$uid"
        emitAll(
            userDocRef(uid).snapshots()
                .map { snapshot ->
                    val doc = snapshot.toObject(
                        UserProfileDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (doc == null) {
                        UserPreferences(displayName = auth.currentUser?.displayName ?: "NEMO User")
                    } else {
                        val (persona, interests) = decryptPersonaPayload(doc.encryptedPersona, doc.personaIv)
                        UserPreferences(
                            displayName = doc.displayName,
                            language = AppLanguage.fromCode(doc.preferredLanguage),
                            themeMode = ThemeMode.fromName(doc.themeMode),
                            responseTone = ResponseTone.fromName(doc.responseTone),
                            fastModeEnabled = doc.fastModeEnabled,
                            autoSpeakEnabled = doc.autoSpeakEnabled,
                            preferredEngine = AiEngineMode.fromName(doc.preferredEngine),
                            customPersona = persona,
                            interests = interests,
                            lastBackupAtMillis = doc.lastBackupAt?.toDate()?.time,
                            hasCloudE2eeVerifier = !doc.e2eeVerifier.isNullOrBlank()
                        )
                    }
                }
                .catch { error ->
                    rethrowOrLog(error, OperationType.GET, path, uid)
                }
        )
    }

    suspend fun ensureUserProfile(
        defaultLanguage: AppLanguage,
        defaultTheme: ThemeMode
    ): Result<Unit> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val ref = userDocRef(uid)
            docPath = ref.path
            val snap = ref.get().await()
            if (!snap.exists()) {
                val displayName = (auth.currentUser?.displayName ?: "NEMO User").take(100)
                val (encPersona, personaIv) = encryptPersonaPayload("", listOf("Productivity", "Technology"))
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to displayName,
                    "preferredLanguage" to defaultLanguage.code,
                    "themeMode" to defaultTheme.name,
                    "responseTone" to ResponseTone.FRIENDLY.name,
                    "fastModeEnabled" to true,
                    "autoSpeakEnabled" to false,
                    "preferredEngine" to AiEngineMode.AUTO.name,
                    "encryptedPersona" to encPersona,
                    "personaIv" to personaIv,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updatePreferences(prefs: UserPreferences): Result<Unit> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val ref = userDocRef(uid)
            docPath = ref.path
            val (encPersona, personaIv) = encryptPersonaPayload(prefs.customPersona, prefs.interests)
            val updates = mapOf(
                "displayName" to prefs.displayName.take(100).ifBlank { "NEMO User" },
                "preferredLanguage" to prefs.language.code,
                "themeMode" to prefs.themeMode.name,
                "responseTone" to prefs.responseTone.name,
                "fastModeEnabled" to prefs.fastModeEnabled,
                "autoSpeakEnabled" to prefs.autoSpeakEnabled,
                "preferredEngine" to prefs.preferredEngine.name,
                "encryptedPersona" to encPersona.take(4000),
                "personaIv" to personaIv.take(64),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveE2eeVerifier(
        saltB64: String,
        verifierCipherB64: String,
        verifierIvB64: String
    ): Result<Unit> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val ref = userDocRef(uid)
            docPath = ref.path
            ref.update(
                mapOf(
                    "e2eeSalt" to saltB64.take(128),
                    "e2eeVerifier" to verifierCipherB64.take(256),
                    "e2eeVerifierIv" to verifierIvB64.take(64),
                    "lastBackupAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun fetchE2eeVerifier(): Result<Triple<String, String, String>?> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val ref = userDocRef(uid)
            docPath = ref.path
            val doc = ref.get().await().toObject(UserProfileDoc::class.java)
            val salt = doc?.e2eeSalt
            val verifier = doc?.e2eeVerifier
            val iv = doc?.e2eeVerifierIv
            if (!salt.isNullOrBlank() && !verifier.isNullOrBlank() && !iv.isNullOrBlank()) {
                Result.success(Triple(salt, verifier, iv))
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docPath)
            Result.failure(e)
        }
    }

    fun observeConversations(userId: String): Flow<List<ChatConversation>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch" }
        val path = "users/$uid/conversations"
        emitAll(
            conversationsCol(uid)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ConversationDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).map { doc ->
                        val title = crypto.decrypt(doc.encryptedTitle, doc.titleIv).ifBlank { "NEMO" }
                        val preview = crypto.decrypt(doc.encryptedPreview, doc.previewIv)
                        ChatConversation(
                            id = doc.id,
                            title = title,
                            preview = preview,
                            languageCode = doc.languageCode,
                            engineUsed = doc.engineUsed,
                            messageCount = doc.messageCount.toInt(),
                            pinned = doc.pinned,
                            archived = doc.archived,
                            updatedAtMillis = (doc.updatedAt ?: doc.createdAt ?: Timestamp.now()).toDate().time
                        )
                    }.sortedWith(compareByDescending<ChatConversation> { it.pinned }.thenByDescending { it.updatedAtMillis })
                }
                .catch { error ->
                    rethrowOrLog(error, OperationType.LIST, path, uid)
                }
        )
    }

    suspend fun createConversation(
        title: String,
        preview: String,
        languageCode: String,
        engineUsed: String
    ): Result<String> {
        var docPath = "users/unknown/conversations"
        return try {
            val uid = requireUserId()
            val ref = conversationsCol(uid).document()
            docPath = ref.path
            val encTitle = crypto.encrypt(title.take(160))
            val encPreview = crypto.encrypt(preview.take(240))
            val payload = mapOf(
                "userId" to uid,
                "encryptedTitle" to encTitle.ciphertextB64.take(2000),
                "titleIv" to encTitle.ivB64.take(64),
                "encryptedPreview" to encPreview.ciphertextB64.take(2000),
                "previewIv" to encPreview.ivB64.take(64),
                "languageCode" to languageCode.take(10),
                "engineUsed" to engineUsed.take(40),
                "messageCount" to 0L,
                "pinned" to false,
                "archived" to false,
                "encryptionVersion" to 1L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateConversationMeta(
        conversationId: String,
        title: String,
        preview: String,
        languageCode: String,
        engineUsed: String,
        messageCount: Int
    ): Result<Unit> {
        var docPath = "users/unknown/conversations/$conversationId"
        return try {
            val uid = requireUserId()
            require(conversationId.isNotBlank()) { "Conversation ID cannot be blank" }
            val ref = conversationsCol(uid).document(conversationId)
            docPath = ref.path
            val encTitle = crypto.encrypt(title.take(160))
            val encPreview = crypto.encrypt(preview.take(240))
            ref.update(
                mapOf(
                    "encryptedTitle" to encTitle.ciphertextB64.take(2000),
                    "titleIv" to encTitle.ivB64.take(64),
                    "encryptedPreview" to encPreview.ciphertextB64.take(2000),
                    "previewIv" to encPreview.ivB64.take(64),
                    "languageCode" to languageCode.take(10),
                    "engineUsed" to engineUsed.take(40),
                    "messageCount" to messageCount.toLong().coerceAtLeast(0L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun togglePinConversation(conversationId: String, pinned: Boolean, archived: Boolean): Result<Unit> {
        var docPath = "users/unknown/conversations/$conversationId"
        return try {
            val uid = requireUserId()
            val ref = conversationsCol(uid).document(conversationId)
            docPath = ref.path
            ref.update(
                mapOf(
                    "pinned" to pinned,
                    "archived" to archived,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteConversation(conversationId: String): Result<Unit> {
        var docPath = "users/unknown/conversations/$conversationId"
        return try {
            val uid = requireUserId()
            val msgsSnap = messagesCol(uid, conversationId).get().await()
            for (doc in msgsSnap.documents) {
                doc.reference.delete().await()
            }
            val ref = conversationsCol(uid).document(conversationId)
            docPath = ref.path
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeMessages(userId: String, conversationId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch" }
        val path = "users/$uid/conversations/$conversationId/messages"
        emitAll(
            messagesCol(uid, conversationId)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        EncryptedMessageDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).map { doc ->
                        ChatMessage(
                            id = doc.id,
                            conversationId = doc.conversationId,
                            role = doc.role,
                            content = crypto.decrypt(doc.encryptedContent, doc.iv),
                            engineUsed = doc.engineUsed,
                            latencyMs = doc.latencyMs,
                            voiceInitiated = doc.voiceInitiated,
                            createdAtMillis = (doc.createdAt ?: Timestamp.now()).toDate().time
                        )
                    }
                }
                .catch { error ->
                    rethrowOrLog(error, OperationType.LIST, path, uid)
                }
        )
    }

    suspend fun addEncryptedMessage(
        conversationId: String,
        role: String,
        plainContent: String,
        engineUsed: String,
        latencyMs: Long,
        voiceInitiated: Boolean
    ): Result<String> {
        var docPath = "users/unknown/conversations/$conversationId/messages"
        return try {
            val uid = requireUserId()
            val ref = messagesCol(uid, conversationId).document()
            docPath = ref.path
            val enc = crypto.encrypt(plainContent.take(32000))
            val payload = mapOf(
                "userId" to uid,
                "conversationId" to conversationId,
                "role" to role,
                "encryptedContent" to enc.ciphertextB64.take(100000),
                "iv" to enc.ivB64.take(64),
                "engineUsed" to engineUsed.take(40),
                "latencyMs" to latencyMs.coerceIn(0L, 600000L),
                "voiceInitiated" to voiceInitiated,
                "encryptionVersion" to 1L,
                "createdAt" to FieldValue.serverTimestamp()
            )
            ref.set(payload).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    fun observeRecentActions(userId: String): Flow<List<RecentActionItem>> = flow {
        val uid = requireUserId()
        check(uid == userId) { "Signed-in user mismatch" }
        val path = "users/$uid/recentActions"
        emitAll(
            recentActionsCol(uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(15)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        RecentActionDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).map { doc ->
                        RecentActionItem(
                            id = doc.id,
                            actionType = ActionType.fromName(doc.actionType),
                            title = crypto.decrypt(doc.encryptedTitle, doc.titleIv),
                            detail = crypto.decrypt(doc.encryptedDetail, doc.detailIv),
                            targetConversationId = doc.targetConversationId,
                            createdAtMillis = (doc.createdAt ?: Timestamp.now()).toDate().time
                        )
                    }
                }
                .catch { error ->
                    rethrowOrLog(error, OperationType.LIST, path, uid)
                }
        )
    }

    suspend fun logRecentAction(
        actionType: ActionType,
        title: String,
        detail: String,
        targetConversationId: String? = null
    ): Result<Unit> {
        var docPath = "users/unknown/recentActions"
        return try {
            val uid = requireUserId()
            val ref = recentActionsCol(uid).document()
            docPath = ref.path
            val encTitle = crypto.encrypt(title.take(160))
            val encDetail = crypto.encrypt(detail.take(300))
            val payload = mapOf(
                "userId" to uid,
                "actionType" to actionType.name,
                "encryptedTitle" to encTitle.ciphertextB64.take(1000),
                "titleIv" to encTitle.ivB64.take(64),
                "encryptedDetail" to encDetail.ciphertextB64.take(2000),
                "detailIv" to encDetail.ivB64.take(64),
                "targetConversationId" to targetConversationId?.take(128),
                "createdAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }
            ref.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun performCloudBackupSync(): Result<Int> {
        var docPath = "users/unknown"
        return try {
            val uid = requireUserId()
            val ref = userDocRef(uid)
            docPath = ref.path
            val convSnap = conversationsCol(uid).get().await()
            ref.update(
                mapOf(
                    "lastBackupAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(convSnap.size())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun restoreCloudSnapshotCount(): Result<Int> {
        var docPath = "users/unknown/conversations"
        return try {
            val uid = requireUserId()
            val convRef = conversationsCol(uid)
            docPath = convRef.path
            val snap = convRef.get().await()
            Result.success(snap.size())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, docPath)
            Result.failure(e)
        }
    }

    private suspend fun rethrowOrLog(
        error: Throwable,
        op: OperationType,
        path: String,
        uid: String
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
        throw error
    }

    private fun encryptPersonaPayload(persona: String, interests: List<String>): Pair<String, String> {
        val json = JSONObject().apply {
            put("persona", persona)
            put("interests", JSONArray(interests))
        }.toString()
        val enc = crypto.encrypt(json)
        return enc.ciphertextB64 to enc.ivB64
    }

    private fun decryptPersonaPayload(cipher: String?, iv: String?): Pair<String, List<String>> {
        if (cipher.isNullOrBlank() || iv.isNullOrBlank()) {
            return "" to listOf("Productivity", "Technology")
        }
        return try {
            val decrypted = crypto.decrypt(cipher, iv)
            val obj = JSONObject(decrypted)
            val persona = obj.optString("persona", "")
            val arr = obj.optJSONArray("interests")
            val list = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        add(arr.optString(i))
                    }
                }
            }.ifEmpty { listOf("Productivity", "Technology") }
            persona to list
        } catch (_: Exception) {
            "" to listOf("Productivity", "Technology")
        }
    }
}
