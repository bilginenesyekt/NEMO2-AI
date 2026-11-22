const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(offsetMs = 2000) {
  return new Date(Date.now() - offsetMs);
}

test("Unauthenticated context: cannot read or write any NEMO collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("conversations").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("recentActions").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Hacker",
      preferredLanguage: "tr",
      themeMode: "DARK",
      responseTone: "FRIENDLY",
      fastModeEnabled: true,
      autoSpeakEnabled: false,
      preferredEngine: "AUTO",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("UserProfile: owner can create and partially update; cross-user and invalid payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      preferredLanguage: "tr",
      themeMode: "DARK",
      responseTone: "FRIENDLY",
      fastModeEnabled: true,
      autoSpeakEnabled: false,
      preferredEngine: "AUTO",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      preferredLanguage: "en",
      themeMode: "LIGHT",
      responseTone: "CONCISE",
      fastModeEnabled: false,
      autoSpeakEnabled: true,
      preferredEngine: "CHATGPT",
      encryptedPersona: "cipher_persona_b64",
      personaIv: "iv_b64",
      updatedAt: pastDate(1000),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      e2eeSalt: "salt_b64",
      e2eeVerifier: "verifier_b64",
      e2eeVerifierIv: "verifier_iv_b64",
      lastBackupAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );

  // Cross-user read & write fail
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "BobHijack",
      updatedAt: pastDate(200),
    })
  );

  // Ghost field & immutable createdAt mutation fail
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(100),
    })
  );
  // Future timestamp fails
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "FutureAlice",
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("Conversations & EncryptedMessages: owner CRUD works, orphan messages and @DocumentId id key rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(10000);

  const convRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("conversations")
    .doc("conv_1");

  await assertSucceeds(
    convRef.set({
      userId: ALICE_UID,
      encryptedTitle: "enc_title_b64",
      titleIv: "title_iv_b64",
      encryptedPreview: "enc_preview_b64",
      previewIv: "preview_iv_b64",
      languageCode: "tr",
      engineUsed: "NEMO_CLOUD",
      messageCount: 0,
      pinned: false,
      archived: false,
      encryptionVersion: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject extra @DocumentId 'id' field in payload
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("conversations")
      .doc("conv_bad")
      .set({
        id: "conv_bad",
        userId: ALICE_UID,
        encryptedTitle: "enc_title_b64",
        titleIv: "title_iv_b64",
        encryptedPreview: "enc_preview_b64",
        previewIv: "preview_iv_b64",
        languageCode: "tr",
        engineUsed: "NEMO_CLOUD",
        messageCount: 0,
        pinned: false,
        archived: false,
        encryptionVersion: 1,
        createdAt: created,
        updatedAt: created,
      })
  );

  // Message inside existing conversation succeeds
  await assertSucceeds(
    convRef.collection("messages").doc("msg_1").set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "user",
      encryptedContent: "enc_msg_b64",
      iv: "msg_iv_b64",
      engineUsed: "NEMO_CLOUD",
      latencyMs: 0,
      voiceInitiated: true,
      encryptionVersion: 1,
      createdAt: pastDate(5000),
    })
  );

  // Orphan message in non-existent conversation fails
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("conversations")
      .doc("missing_conv")
      .collection("messages")
      .doc("msg_orphan")
      .set({
        userId: ALICE_UID,
        conversationId: "missing_conv",
        role: "user",
        encryptedContent: "enc_msg_b64",
        iv: "msg_iv_b64",
        engineUsed: "NEMO_CLOUD",
        latencyMs: 0,
        voiceInitiated: false,
        encryptionVersion: 1,
        createdAt: pastDate(5000),
      })
  );

  // Conversation partial updates work
  await assertSucceeds(
    convRef.update({
      encryptedTitle: "enc_title_2",
      titleIv: "iv_2",
      encryptedPreview: "enc_prev_2",
      previewIv: "piv_2",
      languageCode: "tr",
      engineUsed: "CHATGPT",
      messageCount: 2,
      updatedAt: pastDate(1000),
    })
  );
  await assertSucceeds(
    convRef.update({
      pinned: true,
      archived: false,
      updatedAt: pastDate(500),
    })
  );

  // Messages are immutable (update forbidden)
  await assertFails(
    convRef.collection("messages").doc("msg_1").update({
      encryptedContent: "tampered",
    })
  );

  // List queries match Kotlin repository
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("conversations").get()
  );
  await assertSucceeds(convRef.collection("messages").get());

  // Cross-user denied
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("conversations").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("conversations")
      .doc("conv_1")
      .collection("messages")
      .get()
  );
});

test("RecentActions: owner can create, list, delete; updates and cross-user access fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const actionRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("recentActions")
    .doc("act_1");

  await assertSucceeds(
    actionRef.set({
      userId: ALICE_UID,
      actionType: "VOICE_COMMAND",
      encryptedTitle: "enc_act_title",
      titleIv: "t_iv",
      encryptedDetail: "enc_act_detail",
      detailIv: "d_iv",
      targetConversationId: "conv_1",
      createdAt: pastDate(1000),
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("recentActions").get()
  );

  await assertFails(actionRef.update({ actionType: "CHAT" }));
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("recentActions").get()
  );
  await assertSucceeds(actionRef.delete());
});
