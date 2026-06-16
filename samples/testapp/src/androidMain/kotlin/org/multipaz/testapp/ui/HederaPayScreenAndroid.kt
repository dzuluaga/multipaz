package org.multipaz.testapp.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
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
import org.multipaz.securearea.SecureArea
import org.multipaz.testapp.hedera.HederaAccountCredential
import org.multipaz.testapp.hedera.HederaOperatorConfig
import org.multipaz.testapp.hedera.buildRecipientBoundTransfer
import org.multipaz.testapp.hedera.buildX402Body
import org.multipaz.testapp.hedera.hashscanUrl
import org.multipaz.testapp.hedera.provisionPlatformHederaAccount
import org.multipaz.testapp.hedera.provisionRecoverableAccount
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
    data class ShowRecovery(val recoveryKey: String) : PayState {
        override fun toString(): String = "ShowRecovery(recoveryKey=<redacted>)"
    }
}

/** Whether the wallet's on-chain Hedera account exists yet — drives which button is shown. */
private enum class AccountStatus {
    /** Still reading the credential. */
    Loading,

    /**
     * No on-chain account yet — show "Set up Hedera account". The setup step creates the
     * TEE-backed credential too if it doesn't exist, so this screen is self-contained.
     */
    NeedsSetup,

    /** On-chain account exists (credential certified) — show "Pay". */
    Ready,
}

@Composable
actual fun HederaPayScreen(
    promptModel: PromptModel,
    showToast: (message: String) -> Unit,
    documentStore: DocumentStore,
    secureArea: SecureArea,
) {
    val coroutineScope = rememberCoroutineScope { promptModel }
    var state by remember { mutableStateOf<PayState>(PayState.Idle) }
    var account by remember { mutableStateOf(AccountStatus.Loading) }
    val config = remember { HederaOperatorConfig.fromBuildConfig() }
    val uriHandler = LocalUriHandler.current

    // Decide which button to show by reading the persistent credential's certification state.
    LaunchedEffect(Unit) {
        val credential = findHederaCredential(documentStore)
        account = if (credential?.isCertified == true) AccountStatus.Ready else AccountStatus.NeedsSetup
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("x402 Hedera payment", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pays $AMOUNT_TINYBAR tinybar from a hardware-backed Hedera account. Tap \"Set up Hedera " +
                "account\" to create a TEE-backed key, provision your recoverable on-chain account, " +
                "and save the one-time recovery key. After that, \"Pay\" signs behind a biometric and " +
                "blocky402 sponsors the fee on Hedera testnet.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (config == null) {
            Text(
                "No operator configured. Add HEDERA_OPERATOR_ID / HEDERA_OPERATOR_KEY to " +
                    "local.properties to enable settlement.",
                color = MaterialTheme.colorScheme.error,
            )
        } else if (state !is PayState.ShowRecovery) {
            when (account) {
                AccountStatus.Loading -> CircularProgressIndicator()

                AccountStatus.NeedsSetup -> Button(
                    enabled = state !is PayState.Working,
                    onClick = {
                        coroutineScope.launch {
                            state = PayState.Working("Setting up your recoverable account…")
                            state = try {
                                val result = setUpAccount(documentStore, secureArea, config) { step ->
                                    state = PayState.Working(step)
                                }
                                // Already set up (no recovery key to show) — flip to the Pay button.
                                if (result is PayState.Idle) account = AccountStatus.Ready
                                result
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.printStackTrace()
                                PayState.Failed(e.message ?: e.toString())
                            }
                        }
                    },
                ) {
                    Text("Set up Hedera account")
                }

                AccountStatus.Ready -> Button(
                    enabled = state !is PayState.Working,
                    onClick = {
                        coroutineScope.launch {
                            state = PayState.Working("Authorizing…")
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
                Text(
                    s.hashscanUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { uriHandler.openUri(s.hashscanUrl) },
                )
            }
            is PayState.Failed -> Text(
                "Failed: ${s.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is PayState.ShowRecovery -> RecoveryKeyContent(
                recoveryKey = s.recoveryKey,
                onSaved = {
                    // Account is now set up; drop the only reference to the key and show the Pay button.
                    account = AccountStatus.Ready
                    state = PayState.Idle
                },
            )
        }
    }
}

/** The persistent, TEE-backed Hedera credential provisioned with the test documents, if any. */
private suspend fun findHederaCredential(documentStore: DocumentStore): HederaAccountCredential? =
    documentStore.listDocuments()
        .flatMap { it.getCredentials() }
        .filterIsInstance<HederaAccountCredential>()
        .firstOrNull()

/**
 * Self-contained setup: creates the TEE-backed Hedera credential if it doesn't exist yet, then the
 * born-recoverable on-chain account (operator-funded, 1-of-2 threshold), persists its id on the
 * credential, and surfaces the one-time recovery key. No biometric — the TEE key only signs at
 * payment time. Returns [PayState.ShowRecovery].
 */
private suspend fun setUpAccount(
    documentStore: DocumentStore,
    secureArea: SecureArea,
    config: HederaOperatorConfig,
    onStep: (String) -> Unit,
): PayState {
    var credential = findHederaCredential(documentStore)
    if (credential == null) {
        // No credential yet — create the hardware-backed one here (gated on KeyMint 2.0+).
        onStep("Creating your Hedera account credential…")
        val status = provisionPlatformHederaAccount(documentStore, secureArea)
        credential = findHederaCredential(documentStore)
            ?: throw IllegalStateException(
                status ?: "Could not create a Hedera Account credential on this device.",
            )
    }
    // Already set up (e.g. a stale screen) — nothing to do, no recovery key to re-show.
    if (credential.isCertified) return PayState.Idle

    onStep("Creating your recoverable account…")
    val result = provisionRecoverableAccount(config, credential.hederaPublicKey())
    credential.certify(result.accountId.encodeToByteString())
    // certify() persists the credential but does NOT emit a document-changed event, so DocumentModel
    // keeps its stale (pre-certification, empty) claims until an app restart. Mark the document
    // provisioned — a real metadata change that emits the event, so the account claims rebuild now.
    credential.document.edit { provisioned = true }
    // Known limitation (testnet demo): the recovery key lives only in this PayState and is dropped
    // on Continue. If the OS kills the process while this screen shows, the key is lost and the
    // (already-certified) account becomes unrecoverable. The screen warns the user to keep it open.
    // A hardened build would persist the key to encrypted saved-instance-state.
    return PayState.ShowRecovery(result.recoveryPrivateKey)
}

/**
 * Signs the recipient-bound transfer with the TEE key (biometric) and settles it via blocky402.
 * Requires the account to already be set up (see [setUpAccount]).
 */
private suspend fun pay(
    documentStore: DocumentStore,
    config: HederaOperatorConfig,
    onStep: (String) -> Unit,
): PayState {
    val merchant = config.operatorId // the operator doubles as the demo merchant
    val credential = findHederaCredential(documentStore)
        ?: throw IllegalStateException("No Hedera Account in this wallet.")
    check(credential.isCertified) {
        "Hedera account not set up yet — tap \"Set up Hedera account\" first."
    }
    val accountId = credential.issuerProvidedData.decodeToString()

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
