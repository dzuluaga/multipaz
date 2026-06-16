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
 * LIVE: confirm blocky402 settles a payment from a 1-of-2 threshold-keyed account, signed only by
 * the TEE leaf. Opt-in via HEDERA_LIVE=true. The TEE leaf is simulated here with a SoftwareSecureArea
 * credential key; the recovery leaf is a discarded software key.
 *
 *   HEDERA_LIVE=true ./gradlew :samples:testapp:testBlueDebugUnitTest \
 *     --tests org.multipaz.testapp.hedera.HederaRecoverableLiveTest
 *
 * Resolves the design's open risk: does the facilitator accept a threshold-keyed payer?
 */
class HederaRecoverableLiveTest {
    @Test
    fun `live - threshold-keyed account settles with the tee-leaf signature alone`() = runBlocking {
        Assume.assumeTrue("set HEDERA_LIVE=true to run", System.getenv("HEDERA_LIVE") == "true")
        val config = HederaOperatorConfig.fromBuildConfig()
        Assume.assumeTrue("no operator in local.properties/BuildConfig", config != null)
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
            check(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()} from $url: ${resp.body()}" }
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
