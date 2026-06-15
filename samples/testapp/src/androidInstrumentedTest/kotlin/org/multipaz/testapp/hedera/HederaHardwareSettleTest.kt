package org.multipaz.testapp.hedera

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.multipaz.context.initializeApplication
import org.multipaz.crypto.Algorithm
import org.multipaz.document.buildDocumentStore
import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.assertTrue

/**
 * The hardware proof: the same provision→sign→settle flow as the host test, but the credential's
 * Ed25519 key lives in the device's TEE via [AndroidKeystoreSecureArea] — non-extractable, never
 * in app RAM. Runs on a real device; the Hedera SDK's native gRPC transport and the phone's own
 * network make this the natural home (no host-JVM grpc shim, no sandbox).
 *
 * Opt-in (it spends testnet funds):
 *   ./gradlew :samples:testapp:connectedBlueDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=org.multipaz.testapp.hedera.HederaHardwareSettleTest \
 *     -Pandroid.testInstrumentationRunnerArguments.hederaLive=true
 */
@RunWith(AndroidJUnit4::class)
class HederaHardwareSettleTest {
    private val tag = "HederaHw"

    @Before
    fun setUp() {
        initializeApplication(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
    }

    @Test
    fun teeKeyedCredentialSettlesViaFacilitator() = runBlocking {
        Assume.assumeTrue(
            "pass -Pandroid.testInstrumentationRunnerArguments.hederaLive=true",
            InstrumentationRegistry.getArguments().getString("hederaLive") == "true",
        )
        Assume.assumeTrue(
            "device KeyMint lacks Curve25519 (needs KeyMint 2.0+)",
            AndroidKeystoreSecureArea.Capabilities().curve25519Supported,
        )
        val config = HederaOperatorConfig.fromBuildConfig()
        Assume.assumeTrue("no operator in local.properties/BuildConfig", config != null)
        config!!

        val amountTinybar = 12_345L
        val merchant = config.operatorId

        // 1. Hedera account as a credential whose key lives in the TEE.
        val storage = EphemeralStorage()
        val secureArea = AndroidKeystoreSecureArea.create(storage)
        val store = buildDocumentStore(storage, SecureAreaRepository.Builder().add(secureArea).build()) {
            addCredentialImplementation(HederaAccountCredential.CREDENTIAL_TYPE) { doc ->
                HederaAccountCredential(doc)
            }
        }
        val document = store.createDocument(displayName = "Hedera Account")
        val credential = HederaAccountCredential.create(
            document, "hedera", secureArea, "hedera:testnet", null,
            AndroidKeystoreCreateKeySettings.Builder(ByteString())
                .setAlgorithm(Algorithm.ED25519) // no StrongBox => TEE (framework rejects SB Ed25519)
                .build(),
        )
        val keyInfo = secureArea.getKeyInfo(credential.alias)
        Log.i(tag, "TEE Ed25519 key created, strongBox=${keyInfo.isStrongBoxBacked}, " +
            "attestation certs=${keyInfo.attestation.certChain?.certificates?.size}")
        // Hardware-backed: an attestation chain is present and the key is not StrongBox (TEE).
        assertTrue(keyInfo.attestation.certChain!!.certificates.isNotEmpty())
        assertTrue(!keyInfo.isStrongBoxBacked)

        // 2. Provision the on-chain account from the TEE key's public half.
        val accountId = provisionAccount(config, credential.hederaPublicKey()).toString()
        Log.i(tag, "provisioned account $accountId (key = TEE-resident Ed25519)")

        // 3. Sign the recipient-bound transfer inside the TEE; self-verify the wire.
        val tx = buildRecipientBoundTransfer(accountId, merchant, amountTinybar, config.feePayer)
        val signed = signFrozenWithCredential(tx, credential)
        verifyRecipientBoundWire(signed.transactionBase64(), signed.signerPublicKey, merchant, amountTinybar, config.feePayer)

        // 4. Facilitator verify + settle (HttpURLConnection — java.net.http isn't on Android).
        val httpPost: suspend (String, String) -> String = { urlStr, body ->
            withContext(Dispatchers.IO) {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    .bufferedReader().use { it.readText() }
                check(code in 200..299) { "HTTP $code from $urlStr: $resp" }
                resp
            }
        }
        val body = buildX402Body(signed.transactionBase64(), merchant, amountTinybar, config.feePayer)
        val txId = verifyAndSettle(config, body, httpPost)

        Log.i(tag, "SETTLED ✓ $accountId → $merchant, $amountTinybar tinybar (TEE-signed)")
        Log.i(tag, "HashScan: ${hashscanUrl(txId)}")
        assertTrue(txId.isNotBlank())
    }
}
