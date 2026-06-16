# Recoverable Hardware-Backed Hedera Account — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the wallet's Hedera account born recoverable — its on-chain key is a 1-of-2 threshold `[phone-TEE key, recovery key]` — and show the recovery key exactly once (text + QR), then discard it and never persist it.

**Architecture:** A new `provisionRecoverableAccount` creates the account with a `KeyList(threshold=1)` instead of a bare key, returning the recovery private key for one-time display. A one-time `RecoveryKeyContent` composable shows it (+ QR), gated behind an "I've saved it" checkbox, then drops the reference. A `recoverable` flag on `HederaAccountCredential` records intent. Everyday payments are unchanged (the TEE's single signature satisfies 1-of-2). A live testnet test confirms the blocky402 facilitator settles a threshold-keyed payer.

**Tech Stack:** Kotlin/Compose (`samples/testapp`, androidMain), Hedera SDK `com.hedera.hashgraph:sdk:2.59.0` (`KeyList`, `AccountCreateTransaction`), zxing for QR. Tests: `androidUnitTest` (host JVM, `SoftwareSecureArea`), gated live tests via `HEDERA_LIVE=true`.

**Worktree:** `~/tools/git/multipaz/.worktrees/hedera-recoverable-account` (branch `hedera-recoverable-account`). Run gradle from there.

---

## File Structure

- **Modify** `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaSettlement.kt` — add `buildRecoverableAccountKey`, `RecoverableProvisionResult`, `provisionRecoverableAccount`.
- **Modify** `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaAccountCredential.kt` — add a serialized `recoverable` flag.
- **Modify** `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaPlatformProvisioning.android.kt` — create the wallet credential with `recoverable = true`.
- **Create** `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/ui/RecoveryKeyScreen.kt` — the one-time recovery display composable + QR helper.
- **Modify** `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/ui/HederaPayScreenAndroid.kt` — first-pay provisioning uses `provisionRecoverableAccount` and shows the recovery screen once.
- **Modify** `samples/testapp/build.gradle.kts` — add `zxing-core` to `androidMain` if absent.
- **Modify** `samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaSettlementTest.kt` — unit-test `buildRecoverableAccountKey`.
- **Modify** `samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaAccountCredentialTest.kt` — round-trip the `recoverable` flag.
- **Create** `samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaRecoverableLiveTest.kt` — gated live threshold-payer settlement check.

Test/build commands (from the worktree):
- Host unit tests: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.*"`
- Compile Android: `./gradlew :samples:testapp:compileBlueDebugKotlinAndroid`
- Live test: `HEDERA_LIVE=true ./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaRecoverableLiveTest"`

> testapp has Blue/Red product flavors — the task name is `testBlueDebugUnitTest`, not `testDebugUnitTest`.

---

## Task 1: `recoverable` flag on HederaAccountCredential

**Files:**
- Modify: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaAccountCredential.kt`
- Test: `samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaAccountCredentialTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `HederaAccountCredentialTest`:

```kotlin
@Test
fun `recoverable flag survives serialize-reload`() = runBlocking {
    val storage = EphemeralStorage()
    val secureArea = SoftwareSecureArea.create(storage)
    val store = newStore(storage, secureArea)
    val document = store.createDocument(displayName = "Hedera Account")
    HederaAccountCredential.create(
        document, DOMAIN, secureArea, NETWORK, ACCOUNT_ID,
        SoftwareCreateKeySettings.Builder().setAlgorithm(Algorithm.ED25519).build(),
        recoverable = true,
    )
    val documentId = document.identifier

    val reloaded = newStore(storage, secureArea).lookupDocument(documentId)!!
        .getCredentials().filterIsInstance<HederaAccountCredential>().single()
    assertTrue(reloaded.recoverable)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaAccountCredentialTest"`
Expected: FAIL — `create(...)` has no `recoverable` parameter (compile error), and `reloaded.recoverable` is unresolved.

- [ ] **Step 3: Add the field + serialization**

In `HederaAccountCredential.kt`, after the `accountId` property (around line 84) add:

```kotlin
    /** True if this account is set up with a 1-of-2 recovery threshold key. */
    var recoverable: Boolean = false
        private set
```

Change the private constructor (around line 86) to accept and assign it:

```kotlin
    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        secureArea: SecureArea,
        network: String,
        accountId: String?,
        recoverable: Boolean,
    ) : super(document, asReplacementForIdentifier, domain, secureArea) {
        this.network = network
        this.accountId = accountId
        this.recoverable = recoverable
    }
```

In `deserialize` (around line 118) add after the `accountId` line:

```kotlin
        recoverable = dataItem.getOrNull("recoverable")?.asBoolean ?: false
```

In `addSerializedData` (around line 124) add after the `accountId` line:

```kotlin
        builder.put("recoverable", recoverable)
```

In the `create` companion function, add the parameter (default `false`) and pass it through:

```kotlin
        suspend fun create(
            document: Document,
            domain: String,
            secureArea: SecureArea,
            network: String,
            accountId: String?,
            createKeySettings: CreateKeySettings,
            recoverable: Boolean = false,
        ): HederaAccountCredential {
            return HederaAccountCredential(document, null, domain, secureArea, network, accountId, recoverable).apply {
                generateKey(createKeySettings)
            }
        }
```

In `createForExistingAlias`, pass `recoverable = false` in its `HederaAccountCredential(...)` call (it constructs the credential the same way — add the trailing argument so it compiles).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaAccountCredentialTest"`
Expected: PASS (all existing tests + the new one).

- [ ] **Step 5: Commit**

```bash
git add samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaAccountCredential.kt \
        samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaAccountCredentialTest.kt
git commit --signoff -m "testapp: add recoverable flag to HederaAccountCredential"
```

---

## Task 2: `buildRecoverableAccountKey` (the 1-of-2 threshold)

**Files:**
- Modify: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaSettlement.kt`
- Test: `samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaSettlementTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `HederaSettlementTest`:

```kotlin
@Test
fun `buildRecoverableAccountKey is a 1-of-2 threshold of the two keys`() {
    val tee = com.hedera.hashgraph.sdk.PrivateKey.generateED25519().publicKey
    val recovery = com.hedera.hashgraph.sdk.PrivateKey.generateED25519().publicKey
    val keyList = buildRecoverableAccountKey(tee, recovery)
    assertEquals(1, keyList.threshold)
    assertEquals(2, keyList.size)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaSettlementTest"`
Expected: FAIL — `buildRecoverableAccountKey` is unresolved.

- [ ] **Step 3: Add the function**

In `HederaSettlement.kt`, add the import:

```kotlin
import com.hedera.hashgraph.sdk.KeyList
```

and the function (near `provisionAccount`):

```kotlin
/** The account key for a recoverable account: 1-of-2 [phone TEE key, recovery key]. */
fun buildRecoverableAccountKey(
    teePublicKey: PublicKey,
    recoveryPublicKey: PublicKey,
): KeyList = KeyList.of(teePublicKey, recoveryPublicKey).setThreshold(1)
```

> If `setThreshold` does not return the `KeyList` in this SDK version, write it as:
> `KeyList.of(teePublicKey, recoveryPublicKey).also { it.setThreshold(1) }`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaSettlementTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaSettlement.kt \
        samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaSettlementTest.kt
git commit --signoff -m "testapp: buildRecoverableAccountKey (1-of-2 threshold)"
```

---

## Task 3: `provisionRecoverableAccount`

**Files:**
- Modify: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaSettlement.kt`

This creates the account on-chain (network), so there is no host unit test — it is covered by the live test in Task 6. The gate here is "compiles".

- [ ] **Step 1: Add the result type + function**

In `HederaSettlement.kt`:

```kotlin
/** Result of creating a recoverable account: the on-chain id and the one-shot recovery private key. */
data class RecoverableProvisionResult(
    val accountId: String,
    /** Ed25519 recovery private key (DER string) — display ONCE, then drop. Never persist. */
    val recoveryPrivateKey: String,
)

/**
 * Creates a testnet account keyed by a 1-of-2 threshold [TEE key, fresh recovery key], funded by
 * the operator. The recovery private key is generated here and returned for one-time display; it
 * is never written to disk by the app.
 */
suspend fun provisionRecoverableAccount(
    config: HederaOperatorConfig,
    teePublicKey: PublicKey,
    initialBalance: Hbar = Hbar.from(1),
): RecoverableProvisionResult = withContext(Dispatchers.IO) {
    val recovery = PrivateKey.generateED25519()
    val client = Client.forTestnet()
    try {
        client.setOperator(
            AccountId.fromString(config.operatorId),
            PrivateKey.fromString(config.operatorKey),
        )
        val accountId = AccountCreateTransaction()
            .setKey(buildRecoverableAccountKey(teePublicKey, recovery.publicKey))
            .setInitialBalance(initialBalance)
            .execute(client)
            .getReceipt(client)
            .accountId ?: error("AccountCreate returned no account id")
        RecoverableProvisionResult(accountId.toString(), recovery.toString())
    } finally {
        client.close()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :samples:testapp:compileBlueDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaSettlement.kt
git commit --signoff -m "testapp: provisionRecoverableAccount (threshold account + one-shot recovery key)"
```

---

## Task 4: Live threshold-payer settlement check

This is the **risk gate** from the spec: confirm blocky402 settles a payer whose key is a 1-of-2 threshold, with the TEE leaf's single signature. Do it before wiring the UI so we fail fast if the facilitator rejects threshold payers.

**Files:**
- Create: `samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaRecoverableLiveTest.kt`

- [ ] **Step 1: Write the gated live test**

```kotlin
package org.multipaz.testapp.hedera

import com.hedera.hashgraph.sdk.PrivateKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.multipaz.crypto.Algorithm
import org.multipaz.document.buildDocumentStore
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LIVE: confirm blocky402 settles a payment from a 1-of-2 threshold-keyed account, signed only by
 * the TEE leaf. Opt-in via HEDERA_LIVE=true. The TEE leaf is simulated here with a SoftwareSecureArea
 * credential key; the recovery leaf is a discarded software key.
 */
class HederaRecoverableLiveTest {
    @Test
    fun `live - threshold-keyed account settles with the tee-leaf signature alone`() = runBlocking {
        Assume.assumeTrue("set HEDERA_LIVE=true to run", System.getenv("HEDERA_LIVE") == "true")
        val config = HederaOperatorConfig.fromBuildConfig()
        Assume.assumeTrue("no operator in BuildConfig", config != null)
        config!!

        val amountTinybar = 12_345L
        val merchant = config.operatorId

        // Simulated TEE leaf = a SoftwareSecureArea credential key.
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val store = buildDocumentStore(storage, SecureAreaRepository.Builder().add(secureArea).build()) {
            addCredentialImplementation(HederaAccountCredential.CREDENTIAL_TYPE) { doc ->
                HederaAccountCredential(doc)
            }
        }
        val document = store.createDocument(displayName = "Hedera Account")
        val credential = HederaAccountCredential.create(
            document, "hedera", secureArea, "hedera:testnet", null,
            SoftwareCreateKeySettings.Builder().setAlgorithm(Algorithm.ED25519).build(),
            recoverable = true,
        )

        // Create the recoverable account keyed by [credentialKey, recovery].
        val provision = provisionRecoverableAccount(config, credential.hederaPublicKey())
        println("recoverable account ${provision.accountId} (1-of-2 [TEE, recovery])")

        // Sign the transfer with ONLY the TEE leaf (the credential key) and settle.
        val tx = buildRecipientBoundTransfer(provision.accountId, merchant, amountTinybar, config.feePayer)
        val signed = signFrozenWithCredential(tx, credential)
        verifyRecipientBoundWire(signed.transactionBase64(), signed.signerPublicKey, merchant, amountTinybar, config.feePayer)

        val http = HttpClient.newHttpClient()
        val httpPost: suspend (String, String) -> String = { url, body ->
            val resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}: ${resp.body()}" }
            resp.body()
        }
        val txId = verifyAndSettle(
            config,
            buildX402Body(signed.transactionBase64(), merchant, amountTinybar, config.feePayer),
            httpPost,
        )
        println("SETTLED ✓ threshold payer ${provision.accountId}; HashScan ${hashscanUrl(txId)}")
        assertTrue(txId.isNotBlank())
    }
}
```

- [ ] **Step 2: Confirm it skips by default (CI-safe)**

Run: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaRecoverableLiveTest"`
Expected: BUILD SUCCESSFUL, test reported skipped.

- [ ] **Step 3: Run it live (resolves the risk)**

Run: `HEDERA_LIVE=true ./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.HederaRecoverableLiveTest"`
Expected: PASS; stdout prints `SETTLED ✓ threshold payer …`. Read it from the test report:
`grep -rh "SETTLED" samples/testapp/build/test-results/testBlueDebugUnitTest/`.

> If the facilitator REJECTS the threshold payer, STOP and report — the spec's open risk has materialized and the design needs revisiting (e.g. submit directly via the Hedera SDK instead of the facilitator for threshold accounts).

- [ ] **Step 4: Commit**

```bash
git add samples/testapp/src/androidUnitTest/kotlin/org/multipaz/testapp/hedera/HederaRecoverableLiveTest.kt
git commit --signoff -m "testapp: live test that blocky402 settles a threshold-keyed payer"
```

---

## Task 5: RecoveryKeyContent composable (one-time display)

**Files:**
- Create: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/ui/RecoveryKeyScreen.kt`
- Modify: `samples/testapp/build.gradle.kts` (add `zxing-core` to `androidMain` if absent)

No host unit test (UI); verified on-device in Task 6-integration. Gate: compiles.

- [ ] **Step 1: Ensure zxing is available**

Check `samples/testapp/build.gradle.kts` `androidMain` dependencies. If `libs.zxing.core` (catalog: `zxing-core = { module = "com.google.zxing:core", ... }`) is not present, add to the `androidMain` dependencies block:

```kotlin
                implementation(libs.zxing.core)
```

- [ ] **Step 2: Write the composable**

Create `RecoveryKeyScreen.kt`:

```kotlin
package org.multipaz.testapp.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders a QR for [text] at [sizePx] pixels. */
private fun qrBitmap(text: String, sizePx: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) for (y in 0 until sizePx) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
    }
    return bmp
}

/**
 * One-time recovery-key display. [recoveryKey] is the Ed25519 recovery private key string.
 * On [onSaved] the caller MUST drop its reference to [recoveryKey] — it is never shown again.
 */
@Composable
fun RecoveryKeyContent(
    recoveryKey: String,
    onSaved: () -> Unit,
) {
    var acknowledged by remember { mutableStateOf(false) }
    val qr = remember(recoveryKey) { qrBitmap(recoveryKey).asImageBitmap() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Back up your recovery key", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Your wallet key lives in this phone's secure hardware (TEE) — it can't be exported, " +
                "even by you.\n\nThis is your RECOVERY key. It's shown ONCE. If you lose this phone, " +
                "it's the only way back into your account. Save it now — scan the QR into a password " +
                "manager, or copy the text below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Image(bitmap = qr, contentDescription = "Recovery key QR code", modifier = Modifier.size(220.dp))
        Text(
            recoveryKey,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            Text("I've saved my recovery key. I understand it won't be shown again.")
        }
        Button(enabled = acknowledged, onClick = onSaved) {
            Text("Continue")
        }
    }
}
```

> Copy-to-clipboard is intentionally omitted (clipboard history is a leak surface); the QR-into-a-password-manager path is the recommended save method. If you add a Copy button later, clear the clipboard on `onSaved`.

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :samples:testapp:compileBlueDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/ui/RecoveryKeyScreen.kt \
        samples/testapp/build.gradle.kts gradle/libs.versions.toml
git commit --signoff -m "testapp: one-time RecoveryKeyContent composable (text + QR)"
```

---

## Task 6: Wire it into provisioning + the Pay flow

**Files:**
- Modify: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaPlatformProvisioning.android.kt`
- Modify: `samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/ui/HederaPayScreenAndroid.kt`

No host unit test (UI + network); verified on-device.

- [ ] **Step 1: Mark provisioned credentials recoverable**

In `HederaPlatformProvisioning.android.kt`, in the `HederaAccountCredential.create(...)` call, add the argument:

```kotlin
        recoverable = true,
```

- [ ] **Step 2: Add a ShowRecovery state and use provisionRecoverableAccount**

In `HederaPayScreenAndroid.kt`:

Add to the `PayState` sealed interface:

```kotlin
    data class ShowRecovery(val recoveryKey: String) : PayState
```

In `pay(...)`, replace the first-time provisioning branch. Current:

```kotlin
    val accountId = if (credential.isCertified) {
        credential.issuerProvidedData.decodeToString()
    } else {
        onStep("Provisioning on-chain account (first payment)…")
        val id = provisionAccount(config, credential.hederaPublicKey()).toString()
        credential.certify(id.encodeToByteString())
        id
    }
```

Change `pay(...)` so it returns `PayState` (either `Done` or `ShowRecovery`) and, on first provisioning, creates a recoverable account and surfaces the recovery key instead of completing the payment:

```kotlin
private suspend fun pay(
    documentStore: DocumentStore,
    config: HederaOperatorConfig,
    onStep: (String) -> Unit,
): PayState {
    val merchant = config.operatorId
    val credential = documentStore.listDocuments()
        .flatMap { it.getCredentials() }
        .filterIsInstance<HederaAccountCredential>()
        .firstOrNull()
        ?: throw IllegalStateException(
            "No Hedera Account in this wallet. Open Document Store → " +
                "\"Create Test Documents in Platform Secure Area\" first.",
        )

    // First time: create the recoverable account and show the recovery key once.
    if (!credential.isCertified) {
        onStep("Creating your recoverable account…")
        val result = provisionRecoverableAccount(config, credential.hederaPublicKey())
        credential.certify(result.accountId.encodeToByteString())
        return PayState.ShowRecovery(result.recoveryPrivateKey)
    }

    val accountId = credential.issuerProvidedData.decodeToString()
    onStep("Authenticate to authorize the payment…")
    val tx = buildRecipientBoundTransfer(accountId, merchant, AMOUNT_TINYBAR, config.feePayer)
    val signed = signFrozenWithCredential(
        tx, credential,
        Reason.HumanReadable(
            title = "Authorize payment",
            subtitle = "Pay $AMOUNT_TINYBAR tinybar to $merchant on Hedera testnet",
            requireConfirmation = true,
        ),
    )
    verifyRecipientBoundWire(signed.transactionBase64(), signed.signerPublicKey, merchant, AMOUNT_TINYBAR, config.feePayer)

    onStep("Settling via blocky402…")
    val body = buildX402Body(signed.transactionBase64(), merchant, AMOUNT_TINYBAR, config.feePayer)
    val txId = verifyAndSettle(config, body) { url, requestBody ->
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(requestBody.toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            check(code in 200..299) { "HTTP $code from $url: $resp" }
            resp
        }
    }
    return PayState.Done(accountId, txId, hashscanUrl(txId))
}
```

Update the button's `onClick` so the result type matches (`pay` now returns `PayState`):

```kotlin
                    coroutineScope.launch {
                        state = PayState.Working("Looking up Hedera account…")
                        state = try {
                            pay(documentStore, config) { step -> state = PayState.Working(step) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                            PayState.Failed(e.message ?: e.toString())
                        }
                    }
```

Add a `ShowRecovery` branch to the `when (val s = state)` render block, rendering the Task-5 composable and dropping the key on continue:

```kotlin
            is PayState.ShowRecovery -> RecoveryKeyContent(
                recoveryKey = s.recoveryKey,
                onSaved = { state = PayState.Idle }, // drops the only reference to the key
            )
```

Add the import:

```kotlin
import org.multipaz.testapp.hedera.provisionRecoverableAccount
```

(`RecoveryKeyContent` is in the same `org.multipaz.testapp.ui` package — no import needed.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :samples:testapp:compileBlueDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Confirm host unit tests still pass**

Run: `./gradlew :samples:testapp:testBlueDebugUnitTest --tests "org.multipaz.testapp.hedera.*"`
Expected: existing tests pass; live tests skipped.

- [ ] **Step 5: Install + verify on the Pixel (manual)**

```bash
./gradlew :samples:testapp:installBlueDebug
```
On the device: Document Store → "Create Test Documents in Platform Secure Area" (provisions the recoverable credential) → x402 Hedera Payment → tap Pay. Expected: a one-time **recovery-key screen** (text + QR + checkbox) appears; tick the box, Continue. Tap Pay again → the payment settles (Paid ✓ + HashScan). Confirm the recovery screen does NOT reappear on later pays.

Verify the threshold on-chain:
```bash
curl -s "https://testnet.mirrornode.hedera.com/api/v1/accounts/<accountId>" | python3 -c "import json,sys;print(json.load(sys.stdin)['key'])"
```
Expected: `_type: ProtobufEncoded` (the 1-of-2 threshold).

- [ ] **Step 6: Commit**

```bash
git add samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/hedera/HederaPlatformProvisioning.android.kt \
        samples/testapp/src/androidMain/kotlin/org/multipaz/testapp/ui/HederaPayScreenAndroid.kt
git commit --signoff -m "testapp: born-recoverable account + one-time recovery key in the Pay flow"
```

---

## Done criteria

- `recoverable` flag round-trips (Task 1) and `buildRecoverableAccountKey` is a 1-of-2 (Task 2) — host unit tests pass.
- Live test confirms blocky402 settles a threshold-keyed payer (Task 4) — the spec's open risk resolved.
- On device: first Pay shows the recovery key once (text + QR), it's never shown again, and payments settle from the threshold account (Task 6).
- The recovery private key is never persisted by the app (only returned for display, then the reference is dropped).
