package org.multipaz.documenttype.knowntypes

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.documenttype.Icon
import org.multipaz.documenttype.TransactionDataType
import org.multipaz.openid.TransactionData
import org.multipaz.util.toBase64Url

/**
 * Payment transaction data type definition for DPC payment authorization flows.
 *
 * This defines the field metadata for the `org.multipaz.transaction_data.payment` type,
 * enabling the consent UI to render payment transaction details generically.
 */
object PaymentTransactionData {
    /** The transaction data type identifier for payment authorization. */
    const val TYPE = "org.multipaz.transaction_data.payment"

    private val transactionDataType = TransactionDataType.Builder(TYPE, "Payment Authorization")
            .addField("payee.name", "Payee", Icon.ACCOUNT_BALANCE)
            .addField("payee.id", "Payee ID", Icon.NUMBERS, userVisible = false)
            .addField("amount", "Amount", Icon.NUMBERS)
            .addField("currency", "Currency", Icon.LANGUAGE, userVisible = false)
            .addField("transaction_id", "Reference", Icon.NUMBERS)
            .addField("merchant_message", "Note", Icon.LANGUAGE)
            .build()

    init {
        TransactionData.registerType(TYPE, transactionDataType)
    }

    /** Returns the [TransactionDataType] describing the fields of a payment transaction. */
    fun getTransactionDataType(): TransactionDataType = transactionDataType

    /**
     * Builds a base64url-encoded payment transaction data item ready for
     * [org.multipaz.openid.TransactionData.parse].
     *
     * @param credentialId the DCQL credential query ID this transaction applies to.
     * @param transactionId a unique identifier for this payment event.
     * @param payeeName the human-readable merchant/payee name.
     * @param payeeId the machine-readable payee identifier.
     * @param amount the payment amount as a decimal string (e.g., "90.00").
     * @param currency the ISO 4217 currency code (e.g., "USD").
     * @param merchantMessage optional explanatory text shown on the consent screen.
     * @param hashAlgorithm the hash algorithm for transaction data binding (default: "sha-256").
     * @return a base64url-encoded string suitable for passing to [org.multipaz.openid.TransactionData.parse].
     */
    fun encode(
        credentialId: String,
        transactionId: String,
        payeeName: String,
        payeeId: String,
        amount: String,
        currency: String,
        merchantMessage: String? = null,
        hashAlgorithm: String = "sha-256"
    ): String {
        require(amount.toDoubleOrNull() != null && amount.toDouble() > 0) {
            "amount must be a positive decimal number, got: $amount"
        }
        val json = buildJsonObject {
            put("type", TYPE)
            putJsonArray("credential_ids") { add(JsonPrimitive(credentialId)) }
            putJsonArray("transaction_data_hashes_alg") { add(JsonPrimitive(hashAlgorithm)) }
            put("transaction_id", transactionId)
            putJsonObject("payee") {
                put("name", payeeName)
                put("id", payeeId)
            }
            put("currency", currency)
            put("amount", amount)
            merchantMessage?.let { put("merchant_message", it) }
        }
        return json.toString().encodeToByteArray().toBase64Url()
    }

    /**
     * Creates parsed payment transaction data ready for use in the consent flow.
     *
     * This is the recommended way to create payment transaction data — it handles
     * encoding, parsing, and type registration in a single call.
     *
     * Example usage:
     * ```
     * val transactionData = PaymentTransactionData.create(
     *     credentialId = "payment_cred",
     *     transactionId = "txn-001",
     *     payeeName = "Coffee Shop",
     *     payeeId = "merchant-coffee-001",
     *     amount = "4.50",
     *     currency = "EUR",
     *     merchantMessage = "Latte and croissant"
     * )
     * ```
     *
     * @return a map of credential ID to transaction data items, ready to pass to
     *   [org.multipaz.prompt.PromptModel.requestConsent] or
     *   [org.multipaz.presentment.PresentmentSource.showConsentPrompt].
     */
    suspend fun create(
        credentialId: String,
        transactionId: String,
        payeeName: String,
        payeeId: String,
        amount: String,
        currency: String,
        merchantMessage: String? = null,
        hashAlgorithm: String = "sha-256"
    ): Map<String, List<TransactionData>> {
        val encoded = encode(
            credentialId = credentialId,
            transactionId = transactionId,
            payeeName = payeeName,
            payeeId = payeeId,
            amount = amount,
            currency = currency,
            merchantMessage = merchantMessage,
            hashAlgorithm = hashAlgorithm
        )
        return TransactionData.parse(listOf(encoded))
    }
}
