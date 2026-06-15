package org.multipaz.testapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import org.multipaz.document.DocumentStore
import org.multipaz.prompt.PromptModel
import org.multipaz.prompt.Reason
import org.multipaz.testapp.hedera.HederaAccountCredential
import org.multipaz.testapp.hedera.HederaOperatorConfig
import org.multipaz.testapp.hedera.buildRecipientBoundTransfer
import org.multipaz.testapp.hedera.buildX402Body
import org.multipaz.testapp.hedera.hashscanUrl
import org.multipaz.testapp.hedera.provisionAccount
import org.multipaz.testapp.hedera.signFrozenWithCredential
import org.multipaz.testapp.hedera.verifyAndSettle
import org.multipaz.testapp.hedera.verifyRecipientBoundWire
import java.net.HttpURLConnection
import java.net.URL

private const val AMOUNT_TINYBAR = 12_345L

private sealed interface PayState {
    data object Idle : PayState
    data class Working(val step: String) : PayState
    data class Done(val accountId: String, val txId: String, val hashscanUrl: String) : PayState
    data class Failed(val message: String) : PayState
}

@Composable
actual fun HederaPayScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    documentStore: DocumentStore,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    var state by remember { mutableStateOf<PayState>(PayState.Idle) }
    val config = remember { HederaOperatorConfig.fromBuildConfig() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("x402 Hedera payment", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pays $AMOUNT_TINYBAR tinybar from the Hedera Account credential in this wallet — its " +
                "Ed25519 key lives in the TEE. Provision it first via \"Create Test Documents in " +
                "Platform Secure Area\". The key signs behind a biometric; blocky402 sponsors the " +
                "fee and submits to Hedera testnet. The account is created once and reused.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (config == null) {
            Text(
                "No operator configured. Add HEDERA_OPERATOR_ID / HEDERA_OPERATOR_KEY to " +
                    "local.properties to enable settlement.",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Button(
                enabled = state !is PayState.Working,
                onClick = {
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
                },
            ) {
                Text("Pay $AMOUNT_TINYBAR tinybar with biometric")
            }
        }

        when (val s = state) {
            is PayState.Idle -> {}
            is PayState.Working -> {
                CircularProgressIndicator()
                Text(s.step, style = MaterialTheme.typography.bodyMedium)
            }
            is PayState.Done -> {
                Text("Paid ✓", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("Account: ${s.accountId}", style = MaterialTheme.typography.bodySmall)
                Text("Tx: ${s.txId}", style = MaterialTheme.typography.bodySmall)
                Text(s.hashscanUrl, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            is PayState.Failed -> Text(
                "Failed: ${s.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private suspend fun pay(
    documentStore: DocumentStore,
    config: HederaOperatorConfig,
    onStep: (String) -> Unit,
): PayState.Done {
    val merchant = config.operatorId // the operator doubles as the demo merchant

    // Use the persistent, TEE-backed credential provisioned with the test documents.
    val credential = documentStore.listDocuments()
        .flatMap { it.getCredentials() }
        .filterIsInstance<HederaAccountCredential>()
        .firstOrNull()
        ?: throw IllegalStateException(
            "No Hedera Account in this wallet. Open Document Store → " +
                "\"Create Test Documents in Platform Secure Area\" first.",
        )

    // The on-chain account is created once and its id persisted on the credential (via the
    // issuer-data slot), so subsequent payments reuse the same account.
    val accountId = if (credential.isCertified) {
        credential.issuerProvidedData.decodeToString()
    } else {
        onStep("Provisioning on-chain account (first payment)…")
        val id = provisionAccount(config, credential.hederaPublicKey()).toString()
        credential.certify(id.encodeToByteString())
        id
    }

    // Sign the recipient-bound transfer inside the TEE — triggers the biometric prompt.
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

    // Settle via blocky402.
    onStep("Settling via blocky402…")
    val body = buildX402Body(signed.transactionBase64(), merchant, AMOUNT_TINYBAR, config.feePayer)
    val txId = verifyAndSettle(config, body) { url, requestBody ->
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
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
