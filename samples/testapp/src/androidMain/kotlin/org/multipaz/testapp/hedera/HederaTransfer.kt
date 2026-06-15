package org.multipaz.testapp.hedera

import com.hedera.hashgraph.sdk.AccountId
import com.hedera.hashgraph.sdk.Hbar
import com.hedera.hashgraph.sdk.PublicKey
import com.hedera.hashgraph.sdk.TransactionId
import com.hedera.hashgraph.sdk.TransferTransaction
import com.hedera.hashgraph.sdk.proto.SignedTransaction
import com.hedera.hashgraph.sdk.proto.TransactionBody
import com.hedera.hashgraph.sdk.proto.TransactionList
import java.util.Base64
import org.multipaz.crypto.EcSignature
import org.multipaz.prompt.Reason

/**
 * The credential ↔ Hedera signing seam (x402 "exact" scheme for Hedera).
 *
 * payTo and the exact amount live inside the client-signed body, and the transaction id account
 * is the facilitator's fee payer, so the facilitator can only append its fee-payer signature and
 * submit, or refuse — any edit to recipient or amount invalidates the client signature. The
 * private key never leaves the secure area: the frozen body bytes go in, an Ed25519 signature
 * comes out (HIP-179 external-signing topology).
 */

/** Builds the recipient-bound, not-yet-fee-payer-signed transfer. */
fun buildRecipientBoundTransfer(
    payerAccountId: String,
    payTo: String,
    amountTinybar: Long,
    feePayer: String,
): TransferTransaction =
    TransferTransaction()
        .addHbarTransfer(AccountId.fromString(payerAccountId), Hbar.fromTinybars(-amountTinybar))
        .addHbarTransfer(AccountId.fromString(payTo), Hbar.fromTinybars(amountTinybar))
        .setTransactionId(TransactionId.generate(AccountId.fromString(feePayer)))
        // Explicit node id lets us freeze without a Client (no network) and pins a single
        // bodyBytes for the external signer to cover.
        .setNodeAccountIds(listOf(AccountId(0, 0, 3)))
        .freeze()

/** A credential-signed transfer plus the evidence needed to verify it independently. */
class SecureAreaSignedTransfer(
    val transaction: TransferTransaction,
    val signerPublicKey: PublicKey,
    /** Each (bodyBytes, signature) pair the secure area produced — one per node id. */
    val signatures: List<Pair<ByteArray, EcSignature>>,
) {
    /** The X-PAYMENT `payload.transaction` value: base64 of the partially-signed tx. */
    fun transactionBase64(): String = Base64.getEncoder().encodeToString(transaction.toBytes())
}

/**
 * Signs a frozen [TransferTransaction] with the credential's secure-area Ed25519 key.
 *
 * The body bytes come from the frozen transaction's own serialization (`SignedTransaction.bodyBytes`
 * is what the sigMap must sign), are signed inside the secure area, and the COSE-encoded signature
 * — for Ed25519 exactly the raw 64-byte R||S the sigMap wants — is injected with `addSignature`.
 */
suspend fun signFrozenWithCredential(
    tx: TransferTransaction,
    credential: HederaAccountCredential,
    unlockReason: Reason = Reason.Unspecified,
): SecureAreaSignedTransfer {
    val hederaKey = credential.hederaPublicKey()
    val signatures = TransactionList.parseFrom(tx.toBytes()).transactionListList.map { wrapped ->
        val bodyBytes = SignedTransaction.parseFrom(wrapped.signedTransactionBytes)
            .bodyBytes.toByteArray()
        val signature = credential.secureArea.sign(credential.alias, bodyBytes, unlockReason)
        tx.addSignature(hederaKey, signature.toCoseEncoded())
        bodyBytes to signature
    }
    return SecureAreaSignedTransfer(tx, hederaKey, signatures)
}

// shard.realm.num as a comparable value; the SDK's proto<->AccountId converters are
// package-private, so compare the numbers themselves.
private fun com.hedera.hashgraph.sdk.proto.AccountID.entityId() =
    Triple(shardNum, realmNum, accountNum)

private fun AccountId.entityId() = Triple(shard, realm, num)

/**
 * Facilitator-grade self-verification of the wire artifact before submission.
 *
 * Deliberately NOT `PublicKey.verifyTransaction`, which short-circuits to true once the key is
 * merely listed on the transaction (which `addSignature` causes) without checking the signature
 * bytes. This re-parses the exact base64 that will cross the wire and checks, per signed
 * transaction: (1) the Ed25519 signature verifies over bodyBytes; (2) net credit to [payTo]
 * equals [amountTinybar] with no other positive receiver; (3) the transaction id account is
 * [feePayer] and the fee payer is not a net sender.
 *
 * @throws IllegalStateException naming the violated rule.
 */
fun verifyRecipientBoundWire(
    transactionB64: String,
    signerPublicKey: PublicKey,
    payTo: String,
    amountTinybar: Long,
    feePayer: String,
) {
    val signerRaw = signerPublicKey.toBytesRaw()
    val payToId = AccountId.fromString(payTo)
    val feePayerId = AccountId.fromString(feePayer)

    val wrapped = TransactionList.parseFrom(Base64.getDecoder().decode(transactionB64))
    check(wrapped.transactionListCount > 0) { "no transactions on the wire" }

    for (transaction in wrapped.transactionListList) {
        val signed = SignedTransaction.parseFrom(transaction.signedTransactionBytes)
        val bodyBytes = signed.bodyBytes.toByteArray()

        val sigPair = signed.sigMap.sigPairList.singleOrNull { pair ->
            val prefix = pair.pubKeyPrefix.toByteArray()
            prefix.isNotEmpty() && signerRaw.size >= prefix.size &&
                signerRaw.copyOf(prefix.size).contentEquals(prefix)
        } ?: error("no signature from the account key in the sigMap")
        check(!sigPair.ed25519.isEmpty) { "account signature is not an Ed25519 sigPair" }
        check(signerPublicKey.verify(bodyBytes, sigPair.ed25519.toByteArray())) {
            "Ed25519 signature does not verify over bodyBytes"
        }

        val body = TransactionBody.parseFrom(bodyBytes)
        check(body.transactionID.accountID.entityId() == feePayerId.entityId()) {
            "transaction id account is not the fee payer"
        }
        check(body.hasCryptoTransfer()) { "body is not a CryptoTransfer" }

        val netByAccount = body.cryptoTransfer.transfers.accountAmountsList
            .groupBy { it.accountID.entityId() }
            .mapValues { (_, amounts) -> amounts.sumOf { it.amount } }
        check(netByAccount[payToId.entityId()] == amountTinybar) {
            "net credit to payTo is ${netByAccount[payToId.entityId()]}, expected $amountTinybar"
        }
        netByAccount.forEach { (account, net) ->
            check(net <= 0 || account == payToId.entityId()) { "unexpected positive receiver $account" }
        }
        check((netByAccount[feePayerId.entityId()] ?: 0) >= 0) { "fee payer is a net sender" }
    }
}
