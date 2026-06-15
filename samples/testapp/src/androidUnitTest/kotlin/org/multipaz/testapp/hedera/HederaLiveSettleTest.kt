package org.multipaz.testapp.hedera

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
 * End-to-end LIVE settlement against Hedera testnet + the blocky402 facilitator.
 *
 * Opt-in only — gated on `HEDERA_LIVE=true` so ordinary test runs never touch the network or
 * spend testnet funds. Requires a funded operator in local.properties (BuildConfig). Run:
 *
 *   HEDERA_LIVE=true ./gradlew :samples:testapp:testBlueDebugUnitTest \
 *     --tests org.multipaz.testapp.hedera.HederaLiveSettleTest
 *
 * Proves the whole chain: a Multipaz credential's Ed25519 key becomes a real on-chain account
 * and pays a recipient-bound HBAR transfer that the facilitator sponsors and submits — with a
 * HashScan receipt printed at the end. (Software SecureArea here; the hardware/TEE variant runs
 * on-device.)
 */
class HederaLiveSettleTest {
    @Test
    fun `live - credential account pays a recipient-bound transfer via blocky402`() = runBlocking {
        Assume.assumeTrue("set HEDERA_LIVE=true to run", System.getenv("HEDERA_LIVE") == "true")
        val config = HederaOperatorConfig.fromBuildConfig()
        Assume.assumeTrue("no operator in local.properties/BuildConfig", config != null)
        config!!

        val amountTinybar = 12_345L
        val merchant = config.operatorId // the operator doubles as the demo merchant (payee)

        // 1. Hedera account as a Multipaz credential.
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
        )

        // 2. Provision the on-chain account from the credential's public key (operator pays).
        val accountId = provisionAccount(config, credential.hederaPublicKey()).toString()
        println("provisioned account $accountId (key = credential's Ed25519 key)")

        // 3. Build + sign the recipient-bound transfer with the credential's key; self-verify.
        val tx = buildRecipientBoundTransfer(accountId, merchant, amountTinybar, config.feePayer)
        val signed = signFrozenWithCredential(tx, credential)
        verifyRecipientBoundWire(signed.transactionBase64(), signed.signerPublicKey, merchant, amountTinybar, config.feePayer)

        // 4. Facilitator verify + settle.
        val http = HttpClient.newHttpClient()
        val httpPost: suspend (String, String) -> String = { url, body ->
            val resp = http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()} from $url: ${resp.body()}" }
            resp.body()
        }
        val body = buildX402Body(signed.transactionBase64(), merchant, amountTinybar, config.feePayer)
        val txId = verifyAndSettle(config, body, httpPost)

        println("SETTLED ✓  $accountId → $merchant, $amountTinybar tinybar")
        println("HashScan: ${hashscanUrl(txId)}")
        assertTrue(txId.isNotBlank())
    }
}
