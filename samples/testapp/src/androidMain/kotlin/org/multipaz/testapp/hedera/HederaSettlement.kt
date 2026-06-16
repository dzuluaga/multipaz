package org.multipaz.testapp.hedera

import com.hedera.hashgraph.sdk.AccountCreateTransaction
import com.hedera.hashgraph.sdk.AccountId
import com.hedera.hashgraph.sdk.Client
import com.hedera.hashgraph.sdk.Hbar
import com.hedera.hashgraph.sdk.KeyList
import com.hedera.hashgraph.sdk.PrivateKey
import com.hedera.hashgraph.sdk.PublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.testapp.BuildConfig

/**
 * Live settlement of an x402 Hedera transfer signed by a [HederaAccountCredential].
 *
 * The operator (a funded testnet account, read from a gitignored local.properties via
 * [BuildConfig]) provisions the credential's account and the blocky402 facilitator sponsors fees
 * and submits. The credential's hardware-resident key is the only thing that signs the value
 * transfer — the operator never touches it.
 */
data class HederaOperatorConfig(
    val operatorId: String,
    val operatorKey: String,
    val facilitatorUrl: String = "https://api.testnet.blocky402.com",
    val feePayer: String = "0.0.7162784", // blocky402 testnet fee payer (Lab 1)
) {
    companion object {
        /** Reads the operator from BuildConfig; null (settlement disabled) if unconfigured. */
        fun fromBuildConfig(): HederaOperatorConfig? {
            val id = BuildConfig.HEDERA_OPERATOR_ID
            val key = BuildConfig.HEDERA_OPERATOR_KEY
            return if (id.isBlank() || key.isBlank()) null else HederaOperatorConfig(id, key)
        }
    }
}

class FacilitatorException(val stage: String, message: String) :
    Exception("facilitator $stage failed: $message")

/** `https://hashscan.io/...` link for a settled transaction id. */
fun hashscanUrl(transactionId: String): String =
    "https://hashscan.io/testnet/transaction/$transactionId"

/** The account key for a recoverable account: 1-of-2 [phone TEE key, recovery key]. */
fun buildRecoverableAccountKey(
    teePublicKey: PublicKey,
    recoveryPublicKey: PublicKey,
    // setThreshold(...) returns Unit in Hedera SDK 2.x, so use .also to keep the expression-body form.
): KeyList = KeyList.of(teePublicKey, recoveryPublicKey).also { it.setThreshold(1) }

/**
 * Creates a testnet account whose key is [accountPublicKey] (the credential's Ed25519 public
 * key), funded with [initialBalance], paid by the operator. Returns the new `0.0.x` id.
 */
suspend fun provisionAccount(
    config: HederaOperatorConfig,
    accountPublicKey: PublicKey,
    initialBalance: Hbar = Hbar.from(1),
): AccountId = withContext(Dispatchers.IO) {
    val client = Client.forTestnet()
    try {
        client.setOperator(
            AccountId.fromString(config.operatorId),
            PrivateKey.fromString(config.operatorKey),
        )
        AccountCreateTransaction()
            .setKeyWithoutAlias(accountPublicKey)
            .setInitialBalance(initialBalance)
            .execute(client)
            .getReceipt(client)
            .accountId ?: error("AccountCreate returned no account id")
    } finally {
        client.close()
    }
}

/** Result of creating a recoverable account: the on-chain id and the one-shot recovery private key. */
data class RecoverableProvisionResult(
    val accountId: String,
    /** Ed25519 recovery private key (DER string) — display ONCE, then drop. Never persist. */
    val recoveryPrivateKey: String,
) {
    // Keep the sensitive key out of logs/crash reports — the auto-generated data-class
    // toString() would otherwise print the full DER private key.
    override fun toString(): String =
        "RecoverableProvisionResult(accountId=$accountId, recoveryPrivateKey=<redacted>)"
}

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
            .setKeyWithoutAlias(buildRecoverableAccountKey(teePublicKey, recovery.publicKey))
            .setInitialBalance(initialBalance)
            .execute(client)
            .getReceipt(client)
            .accountId ?: error("AccountCreate returned no account id")
        // PrivateKey.toString() is the hex-encoded DER (== toStringDER()); round-trips via PrivateKey.fromString().
        RecoverableProvisionResult(accountId.toString(), recovery.toString())
    } finally {
        client.close()
    }
}

/**
 * Runs the x402 `verify` then `settle` against the facilitator, decoupled from the HTTP engine:
 * [httpPost] is `(url, jsonBody) -> jsonResponse`, so the host test can use `java.net.http` and
 * the app a ktor/OkHttp client. Returns the settled Hedera transaction id.
 */
suspend fun verifyAndSettle(
    config: HederaOperatorConfig,
    x402Body: String,
    httpPost: suspend (url: String, body: String) -> String,
): String {
    val verify = Json.parseToJsonElement(httpPost("${config.facilitatorUrl}/verify", x402Body)).jsonObject
    if (verify["isValid"]?.jsonPrimitive?.booleanOrNull != true) {
        throw FacilitatorException("verify", verify["invalidReason"]?.jsonPrimitive?.content ?: "rejected")
    }
    val settle = Json.parseToJsonElement(httpPost("${config.facilitatorUrl}/settle", x402Body)).jsonObject
    if (settle["success"]?.jsonPrimitive?.booleanOrNull != true) {
        throw FacilitatorException("settle", settle["error"]?.jsonPrimitive?.content ?: "rejected")
    }
    return (settle["transactionId"] ?: settle["transaction"])?.jsonPrimitive?.content
        ?: throw FacilitatorException("settle", "no transaction id in response")
}
