package org.multipaz.verifier.request

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import org.junit.Before
import org.junit.Test
import org.multipaz.asn1.ASN1Integer
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tstr
import org.multipaz.cbor.toDataItem
import org.multipaz.cose.Cose
import org.multipaz.cose.CoseLabel
import org.multipaz.cose.CoseNumberLabel
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.knowntypes.DigitalPaymentCredential
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.devicesigned.DeviceNamespaces
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import org.multipaz.mdoc.issuersigned.IssuerSignedItem
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.trustmanagement.TrustResult
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class DpcPolicyEvaluatorTest {

    private lateinit var dsKey: EcPrivateKey
    private lateinit var dsCert: X509Cert

    @Before
    fun setup() = runBlocking {
        dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val now = Clock.System.now()
        dsCert = X509Cert.Builder(
            publicKey = dsKey.publicKey,
            signingKey = AsymmetricKey.anonymous(dsKey, Algorithm.ES256),
            serialNumber = ASN1Integer(1),
            subject = X500Name.fromName("CN=Test DPC Issuer"),
            issuer = X500Name.fromName("CN=Test DPC Issuer"),
            validFrom = now,
            validUntil = now + 365.days,
        ).build()
    }

    private suspend fun createTestDocument(
        claims: Map<String, DataItem> = defaultClaims(),
    ): MdocDocument {
        val namespace = DigitalPaymentCredential.CARD_NAMESPACE
        val issuerSignedItems = claims.entries.mapIndexed { index, (key, value) ->
            key to IssuerSignedItem(
                digestId = index.toLong(),
                random = ByteString(Random.nextBytes(16)),
                dataElementIdentifier = key,
                dataElementValue = value,
            )
        }.toMap()

        val issuerNamespaces = IssuerNamespaces(
            data = mapOf(namespace to issuerSignedItems)
        )

        // Create issuerAuth CoseSign1 with x5chain header containing test cert
        val protectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_ALG),
                Algorithm.ES256.coseAlgorithmIdentifier!!.toDataItem()
            )
        )
        val unprotectedHeaders = mapOf<CoseLabel, DataItem>(
            Pair(
                CoseNumberLabel(Cose.COSE_LABEL_X5CHAIN),
                X509CertChain(listOf(dsCert)).toDataItem()
            )
        )
        val signingKey = AsymmetricKey.anonymous(dsKey, Algorithm.ES256)
        val issuerAuth = Cose.coseSign1Sign(
            signingKey,
            ByteArray(0),
            true,
            protectedHeaders,
            unprotectedHeaders,
        )

        // Dummy device auth (evaluator doesn't use it)
        val deviceAuth = DeviceAuth.Ecdsa(
            signature = Cose.coseSign1Sign(
                signingKey,
                ByteArray(0),
                true,
                emptyMap(),
                emptyMap(),
            )
        )

        return MdocDocument(
            docType = DigitalPaymentCredential.CARD_DOCTYPE,
            issuerAuth = issuerAuth,
            issuerNamespaces = issuerNamespaces,
            deviceAuth = deviceAuth,
            deviceNamespaces = DeviceNamespaces(data = emptyMap()),
            errors = emptyMap(),
        )
    }

    private fun defaultClaims(): Map<String, DataItem> = mapOf(
        "issuer_name" to Tstr("Test Bank"),
        "masked_account_reference" to Tstr("****1234"),
        "holder_name" to Tstr("Test User"),
        "issue_date" to Tstr("2025-01-01"),
        "expiry_date" to Tstr("2027-12-31"),
    )

    private fun trustedTrustManager(): TrustManagerInterface {
        return object : TrustManagerInterface {
            override val identifier: String = "test-trusted"
            override suspend fun getTrustPoints(): List<TrustPoint> = emptyList()
            override suspend fun verify(
                chain: List<X509Cert>,
                atTime: Instant,
            ): TrustResult {
                return TrustResult(isTrusted = true)
            }
        }
    }

    private fun untrustedTrustManager(): TrustManagerInterface {
        return object : TrustManagerInterface {
            override val identifier: String = "test-untrusted"
            override suspend fun getTrustPoints(): List<TrustPoint> = emptyList()
            override suspend fun verify(
                chain: List<X509Cert>,
                atTime: Instant,
            ): TrustResult {
                return TrustResult(isTrusted = false)
            }
        }
    }

    @Test
    fun allChecksPassing() = runTest {
        val document = createTestDocument()
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        assertTrue(decision.eligible)
        assertEquals(5, decision.checks.size)
        assertTrue(decision.checks.all { it.passed })
        assertTrue(decision.evaluatedAt.isNotEmpty())
        assertTrue(decision.expiresAt.isNotEmpty())
    }

    @Test
    fun untrustedIssuer() = runTest {
        val document = createTestDocument()
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = untrustedTrustManager(),
            verificationSucceeded = true,
        )

        assertFalse(decision.eligible)
        val issuerTrust = decision.checks.first { it.checkId == "issuer_trust" }
        assertFalse(issuerTrust.passed)
        assertTrue(issuerTrust.reason.contains("not in trust list"))

        // Assurance level should also fail (untrusted issuer)
        val assurance = decision.checks.first { it.checkId == "assurance_level" }
        assertFalse(assurance.passed)
    }

    @Test
    fun verificationFailed() = runTest {
        val document = createTestDocument()
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = false,
        )

        assertFalse(decision.eligible)
        val proofCheck = decision.checks.first { it.checkId == "proof_verification" }
        assertFalse(proofCheck.passed)
        val walletBinding = decision.checks.first { it.checkId == "wallet_binding" }
        assertFalse(walletBinding.passed)
    }

    @Test
    fun expiredCredential() = runTest {
        val claims = defaultClaims().toMutableMap()
        claims["expiry_date"] = Tstr("2020-01-01")
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        assertFalse(decision.eligible)
        val expiryCheck = decision.checks.first { it.checkId == "credential_expiry" }
        assertFalse(expiryCheck.passed)
        assertTrue(expiryCheck.reason.contains("expired"))
        assertEquals("2020-01-01", decision.credentialExpiryDate)
    }

    @Test
    fun missingMandatoryClaims() = runTest {
        val claims = mapOf<String, DataItem>(
            "issuer_name" to Tstr("Test Bank"),
        )
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        assertFalse(decision.eligible)
        val assurance = decision.checks.first { it.checkId == "assurance_level" }
        assertFalse(assurance.passed)
        assertTrue(assurance.reason.contains("Missing mandatory claims"))
    }

    @Test
    fun multipleFailures() = runTest {
        val claims = mapOf<String, DataItem>(
            "issuer_name" to Tstr("Test Bank"),
            "expiry_date" to Tstr("2020-01-01"),
        )
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = untrustedTrustManager(),
            verificationSucceeded = false,
        )

        assertFalse(decision.eligible)
        val failedChecks = decision.checks.filter { !it.passed }
        // issuer_trust, proof_verification, assurance_level, wallet_binding, credential_expiry
        assertEquals(5, failedChecks.size)
    }

    @Test
    fun noExpiryDate() = runTest {
        val claims = defaultClaims().toMutableMap()
        claims.remove("expiry_date")
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        // credential_expiry should pass (non-expiring)
        val expiryCheck = decision.checks.first { it.checkId == "credential_expiry" }
        assertTrue(expiryCheck.passed)
        assertTrue(expiryCheck.reason.contains("non-expiring"))

        // But assurance_level should fail (missing mandatory claim)
        val assurance = decision.checks.first { it.checkId == "assurance_level" }
        assertFalse(assurance.passed)
    }

    // --- Edge case tests (T019) ---

    @Test
    fun allMandatoryClaimsButUntrustedIssuer() = runTest {
        val document = createTestDocument()
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = untrustedTrustManager(),
            verificationSucceeded = true,
        )

        assertFalse(decision.eligible)
        val issuerTrust = decision.checks.first { it.checkId == "issuer_trust" }
        assertFalse(issuerTrust.passed)
        val assurance = decision.checks.first { it.checkId == "assurance_level" }
        assertFalse(assurance.passed)
        assertTrue(assurance.reason.contains("not trusted"))
        // credential_expiry and proof should still pass
        val expiryCheck = decision.checks.first { it.checkId == "credential_expiry" }
        assertTrue(expiryCheck.passed)
        val proofCheck = decision.checks.first { it.checkId == "proof_verification" }
        assertTrue(proofCheck.passed)
    }

    @Test
    fun missingOnlyOptionalClaimsStillEligible() = runTest {
        // All 5 mandatory claims present, no extra optional claims
        val claims = defaultClaims()
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        assertTrue(decision.eligible)
        assertTrue(decision.checks.all { it.passed })
    }

    @Test
    fun futureIssueDate() = runTest {
        val claims = defaultClaims().toMutableMap()
        claims["issue_date"] = Tstr("2099-01-01")
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        // Future issue_date should still pass — evaluator only checks expiry, not issue_date validity
        assertTrue(decision.eligible)
        assertTrue(decision.checks.all { it.passed })
    }

    @Test
    fun malformedExpiryDate() = runTest {
        val claims = defaultClaims().toMutableMap()
        claims["expiry_date"] = Tstr("not-a-date")
        val document = createTestDocument(claims)
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        assertFalse(decision.eligible)
        val expiryCheck = decision.checks.first { it.checkId == "credential_expiry" }
        assertFalse(expiryCheck.passed)
        assertTrue(expiryCheck.reason.contains("Failed to parse"))
    }

    @Test
    fun decisionTimestampsArePopulated() = runTest {
        val document = createTestDocument()
        val decision = DpcPolicyEvaluator.evaluate(
            document = document,
            trustManager = trustedTrustManager(),
            verificationSucceeded = true,
        )

        assertTrue(decision.evaluatedAt.isNotEmpty())
        assertTrue(decision.expiresAt.isNotEmpty())
        // expiresAt should be after evaluatedAt
        val evaluated = Instant.parse(decision.evaluatedAt)
        val expires = Instant.parse(decision.expiresAt)
        assertTrue(expires > evaluated)
    }
}
