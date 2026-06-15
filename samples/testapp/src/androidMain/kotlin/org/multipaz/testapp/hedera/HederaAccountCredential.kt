package org.multipaz.testapp.hedera

import com.hedera.hashgraph.sdk.PublicKey
import kotlin.time.Instant
import org.multipaz.cbor.CborBuilder
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MapBuilder
import org.multipaz.claim.Claim
import org.multipaz.credential.SecureAreaBoundCredential
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyOkp
import org.multipaz.document.Document
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.securearea.CreateKeySettings
import org.multipaz.securearea.SecureArea

/**
 * A Hedera account modelled as a Multipaz credential.
 *
 * The credential's [SecureArea] key IS the account's on-chain key: a hardware-backed Ed25519
 * key (on a KeyMint 2.0+ device) whose public half is the Hedera account key. The credential
 * therefore *is* the wallet's payment instrument — holding the key in the platform's secure
 * hardware, bound to a [Document], managed by the [org.multipaz.document.DocumentStore].
 *
 * Unlike an issued mdoc/SD-JWT credential, a Hedera account is self-custodial: there is no
 * issuer certification, so [getClaims] returns the locally-known account facts and
 * [extractValidityFromIssuerData] reports an unbounded validity.
 */
class HederaAccountCredential : SecureAreaBoundCredential {
    companion object {
        const val CREDENTIAL_TYPE: String = "HederaAccountCredential"

        /**
         * Creates a [HederaAccountCredential] with a freshly-generated Ed25519 key.
         *
         * [createKeySettings] must request [org.multipaz.crypto.Algorithm.ED25519]; on Android
         * the caller is responsible for gating on `Capabilities().curve25519Supported` and
         * falling back to a software [SecureArea] where the hardware lacks Curve25519.
         *
         * @param accountId the provisioned `shard.realm.num` id, or null until the account is
         *   created on-chain (see [setAccountId]).
         */
        suspend fun create(
            document: Document,
            domain: String,
            secureArea: SecureArea,
            network: String,
            accountId: String?,
            createKeySettings: CreateKeySettings,
        ): HederaAccountCredential {
            return HederaAccountCredential(document, null, domain, secureArea, network, accountId).apply {
                generateKey(createKeySettings)
            }
        }

        /**
         * Binds the credential to a key that already exists in [secureArea].
         *
         * This is the post-provisioning path: generate the Ed25519 key, create the on-chain
         * account from its public key, then bind the credential to that key with the now-known
         * [accountId]. Avoids mutating a persisted credential (whose serialized state is fixed at
         * creation).
         */
        suspend fun createForExistingAlias(
            document: Document,
            domain: String,
            secureArea: SecureArea,
            network: String,
            accountId: String,
            existingKeyAlias: String,
        ): HederaAccountCredential {
            return HederaAccountCredential(document, null, domain, secureArea, network, accountId).apply {
                useExistingKey(existingKeyAlias)
            }
        }
    }

    /** CAIP-2 network this account lives on, e.g. `hedera:testnet`. */
    lateinit var network: String
        private set

    /** Provisioned entity id (`0.0.x`), or null until the account is created on-chain. */
    var accountId: String? = null
        private set

    private constructor(
        document: Document,
        asReplacementForIdentifier: String?,
        domain: String,
        secureArea: SecureArea,
        network: String,
        accountId: String?,
    ) : super(document, asReplacementForIdentifier, domain, secureArea) {
        this.network = network
        this.accountId = accountId
    }

    constructor(document: Document) : super(document)

    /**
     * The Hedera account key derived from the secure-area key.
     *
     * The secure area holds an Ed25519 key whose raw 32-byte public value is exactly what
     * [PublicKey.fromBytesED25519] consumes — no conversion, the COSE/OKP encoding and the
     * Hedera encoding agree.
     */
    suspend fun hederaPublicKey(): PublicKey {
        val publicKey = secureArea.getKeyInfo(alias).publicKey
        require(publicKey is EcPublicKeyOkp && publicKey.curve == EcCurve.ED25519) {
            "Hedera account key must be Ed25519, got ${publicKey.curve}"
        }
        return PublicKey.fromBytesED25519(publicKey.x)
    }

    override val credentialType: String
        get() = CREDENTIAL_TYPE

    override suspend fun deserialize(dataItem: DataItem) {
        super.deserialize(dataItem)
        network = dataItem["network"].asTstr
        accountId = dataItem.getOrNull("accountId")?.asTstr
    }

    override fun addSerializedData(builder: MapBuilder<CborBuilder>) {
        super.addSerializedData(builder)
        builder.put("network", network)
        accountId?.let { builder.put("accountId", it) }
    }

    // Self-custodial: no issuer claims and unbounded validity. The credential exists to hold and
    // exercise the account key, not to be presented as an issued claim set.
    override suspend fun getClaims(documentTypeRepository: DocumentTypeRepository?): List<Claim> =
        emptyList()

    override suspend fun extractValidityFromIssuerData(): Pair<Instant, Instant> =
        Instant.DISTANT_PAST to Instant.DISTANT_FUTURE
}
