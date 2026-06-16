package org.multipaz.testapp.hedera

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPublicKeyOkp
import org.multipaz.document.Document
import org.multipaz.document.DocumentStore
import org.multipaz.document.buildDocumentStore
import org.multipaz.securearea.SecureAreaRepository
import org.multipaz.securearea.software.SoftwareCreateKeySettings
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.Storage
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step 1 of the x402 Hedera demo: a Hedera account stored as a Multipaz credential whose
 * SecureArea key is the on-chain account key. JVM-host test against SoftwareSecureArea; the
 * hardware (AndroidKeystoreSecureArea / TEE) variant is exercised on-device.
 */
class HederaAccountCredentialTest {
    private companion object {
        const val FEE_PAYER = "0.0.7162784" // blocky402 testnet fee payer (Lab 1)
        const val PAYER = "0.0.1111"
        const val MERCHANT = "0.0.2222"
        const val AMOUNT = 12_345L
        const val NETWORK = "hedera:testnet"
        const val ACCOUNT_ID = "0.0.1111"
        const val DOMAIN = "hedera"
    }

    private suspend fun newStore(
        storage: Storage,
        secureArea: SoftwareSecureArea,
    ): DocumentStore {
        val repo = SecureAreaRepository.Builder().add(secureArea).build()
        return buildDocumentStore(storage, repo) {
            addCredentialImplementation(HederaAccountCredential.CREDENTIAL_TYPE) { doc ->
                HederaAccountCredential(doc)
            }
        }
    }

    private suspend fun newCredential(
        store: DocumentStore,
        secureArea: SoftwareSecureArea,
        accountId: String? = ACCOUNT_ID,
    ): Pair<Document, HederaAccountCredential> {
        val document = store.createDocument(displayName = "Hedera Account")
        val credential = HederaAccountCredential.create(
            document = document,
            domain = DOMAIN,
            secureArea = secureArea,
            network = NETWORK,
            accountId = accountId,
            createKeySettings = SoftwareCreateKeySettings.Builder()
                .setAlgorithm(Algorithm.ED25519)
                .build(),
        )
        return document to credential
    }

    @Test
    fun `credential key bridges to a hedera ed25519 public key`() = runBlocking {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val (_, credential) = newCredential(newStore(storage, secureArea), secureArea)

        val okp = secureArea.getKeyInfo(credential.alias).publicKey as EcPublicKeyOkp
        assertEquals(EcCurve.ED25519, okp.curve)

        val hederaKey = credential.hederaPublicKey()
        assertTrue(hederaKey.isED25519)
        assertContentEquals(okp.x, hederaKey.toBytesRaw())
        assertEquals(NETWORK, credential.network)
        assertEquals(ACCOUNT_ID, credential.accountId)
    }

    @Test
    fun `credential signs a recipient-bound transfer and the wire self-verifies`() = runBlocking {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val (_, credential) = newCredential(newStore(storage, secureArea), secureArea)

        val tx = buildRecipientBoundTransfer(PAYER, MERCHANT, AMOUNT, FEE_PAYER)
        val signed = signFrozenWithCredential(tx, credential)

        val (bodyBytes, signature) = signed.signatures.single()
        assertEquals(64, signature.toCoseEncoded().size)
        // Independent Multipaz verification of the exact bytes the SDK signed.
        Crypto.checkSignature(
            secureArea.getKeyInfo(credential.alias).publicKey,
            bodyBytes,
            Algorithm.ED25519,
            signature,
        )
        // Facilitator-grade wire check.
        verifyRecipientBoundWire(signed.transactionBase64(), signed.signerPublicKey, MERCHANT, AMOUNT, FEE_PAYER)
    }

    @Test
    fun `recipient binding - a different amount fails wire verification`() = runBlocking {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val (_, credential) = newCredential(newStore(storage, secureArea), secureArea)

        val signed = signFrozenWithCredential(
            buildRecipientBoundTransfer(PAYER, MERCHANT, AMOUNT, FEE_PAYER),
            credential,
        )
        val tampered = buildRecipientBoundTransfer(PAYER, MERCHANT, AMOUNT * 100, FEE_PAYER)
        tampered.addSignature(signed.signerPublicKey, signed.signatures.single().second.toCoseEncoded())
        val tamperedB64 = java.util.Base64.getEncoder().encodeToString(tampered.toBytes())
        assertFailsWith<IllegalStateException> {
            verifyRecipientBoundWire(tamperedB64, signed.signerPublicKey, MERCHANT, AMOUNT * 100, FEE_PAYER)
        }
        Unit // JUnit4 @Test methods must return void
    }

    @Test
    fun `x402 body matches the lab 1 wire shape`() = runBlocking {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val (_, credential) = newCredential(newStore(storage, secureArea), secureArea)
        val signed = signFrozenWithCredential(
            buildRecipientBoundTransfer(PAYER, MERCHANT, AMOUNT, FEE_PAYER),
            credential,
        )

        val body = Json.parseToJsonElement(
            buildX402Body(signed.transactionBase64(), MERCHANT, AMOUNT, FEE_PAYER, NETWORK)
        ).jsonObject
        val req = body["paymentRequirements"]!!.jsonObject
        assertEquals("exact", req["scheme"]!!.jsonPrimitive.content)
        assertEquals(NETWORK, req["network"]!!.jsonPrimitive.content)
        assertEquals("0.0.0", req["asset"]!!.jsonPrimitive.content)
        assertEquals(MERCHANT, req["payTo"]!!.jsonPrimitive.content)
        assertEquals(AMOUNT.toString(), req["amount"]!!.jsonPrimitive.content)
        assertTrue(req["maxTimeoutSeconds"]!!.jsonPrimitive.content.toInt() >= 1)
        assertEquals(req, body["paymentPayload"]!!.jsonObject["accepted"]!!.jsonObject)
    }

    @Test
    fun `recoverable flag survives serialize-reload`() = runBlocking {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val store = newStore(storage, secureArea)
        val document = store.createDocument(displayName = "Hedera Account")
        HederaAccountCredential.create(
            document = document,
            domain = DOMAIN,
            secureArea = secureArea,
            network = NETWORK,
            accountId = ACCOUNT_ID,
            createKeySettings = SoftwareCreateKeySettings.Builder().setAlgorithm(Algorithm.ED25519).build(),
            recoverable = true,
        )
        val documentId = document.identifier

        val reloaded = newStore(storage, secureArea).lookupDocument(documentId)!!
            .getCredentials().filterIsInstance<HederaAccountCredential>().single()
        assertTrue(reloaded.recoverable)

        // The false case is the one most likely to silently break if a serialization
        // direction is dropped: a default-false field round-trips to false whether or not
        // it is actually persisted. Use a distinct document so the two don't collide.
        val nonRecoverableDoc = store.createDocument(displayName = "Hedera Account (non-recoverable)")
        HederaAccountCredential.create(
            document = nonRecoverableDoc,
            domain = DOMAIN,
            secureArea = secureArea,
            network = NETWORK,
            accountId = ACCOUNT_ID,
            createKeySettings = SoftwareCreateKeySettings.Builder().setAlgorithm(Algorithm.ED25519).build(),
            recoverable = false,
        )
        val nonRecoverableId = nonRecoverableDoc.identifier

        val reloadedNonRecoverable = newStore(storage, secureArea).lookupDocument(nonRecoverableId)!!
            .getCredentials().filterIsInstance<HederaAccountCredential>().single()
        assertFalse(reloadedNonRecoverable.recoverable)
    }

    @Test
    fun `credential survives serialize-reload and the key still signs`() = runBlocking {
        val storage = EphemeralStorage()
        val secureArea = SoftwareSecureArea.create(storage)
        val (document, _) = newCredential(newStore(storage, secureArea), secureArea)
        val documentId = document.identifier

        // Fresh DocumentStore over the same storage forces a real deserialize of the credential.
        val reloadedStore = newStore(storage, secureArea)
        val reloadedDoc = reloadedStore.lookupDocument(documentId)!!
        val reloaded = reloadedDoc.getCredentials().filterIsInstance<HederaAccountCredential>().single()

        assertEquals(NETWORK, reloaded.network)
        assertEquals(ACCOUNT_ID, reloaded.accountId)

        // The reloaded credential's key is still usable end to end.
        val signed = signFrozenWithCredential(
            buildRecipientBoundTransfer(PAYER, MERCHANT, AMOUNT, FEE_PAYER),
            reloaded,
        )
        verifyRecipientBoundWire(signed.transactionBase64(), signed.signerPublicKey, MERCHANT, AMOUNT, FEE_PAYER)
    }
}
