package org.multipaz.verifier.request

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.Tstr
import org.multipaz.documenttype.knowntypes.DigitalPaymentCredential
import org.multipaz.mdoc.response.MdocDocument
import org.multipaz.trustmanagement.TrustManagerInterface
import org.multipaz.trustmanagement.TrustResult
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Serializable
data class PolicyCheckResult(
    val checkId: String,
    val checkName: String,
    val passed: Boolean,
    val reason: String
)

@Serializable
data class EligibilityDecision(
    val eligible: Boolean,
    val checks: List<PolicyCheckResult>,
    val evaluatedAt: String,
    val expiresAt: String,
    val credentialExpiryDate: String? = null
)

private val PRECHECK_DECISION_VALIDITY = 15.minutes

internal object DpcPolicyEvaluator {
    suspend fun evaluate(
        document: MdocDocument,
        trustManager: TrustManagerInterface,
        verificationSucceeded: Boolean,
    ): EligibilityDecision {
        val checks = mutableListOf<PolicyCheckResult>()
        val now = Clock.System.now()

        // 1. Issuer trust check
        val trustResult = trustManager.verify(document.issuerCertChain.certificates)
        checks.add(checkIssuerTrust(trustResult, document))

        // 2. Proof/device response verification
        checks.add(checkProofVerification(verificationSucceeded))

        // 3. Assurance level policy
        checks.add(checkAssuranceLevel(trustResult.isTrusted, document))

        // 4. Wallet binding method
        checks.add(checkWalletBinding(verificationSucceeded))

        // 5. Credential expiry
        checks.add(checkCredentialExpiry(document))

        val credentialExpiryDate = extractExpiryDate(document)

        return EligibilityDecision(
            eligible = checks.all { it.passed },
            checks = checks,
            evaluatedAt = now.toString(),
            expiresAt = (now + PRECHECK_DECISION_VALIDITY).toString(),
            credentialExpiryDate = credentialExpiryDate,
        )
    }

    private fun checkIssuerTrust(
        trustResult: TrustResult,
        document: MdocDocument,
    ): PolicyCheckResult {
        return if (trustResult.isTrusted) {
            val name = trustResult.trustPoints.firstOrNull()?.metadata?.displayName
                ?: trustResult.trustPoints.firstOrNull()?.certificate?.subject?.name
                ?: "Unknown"
            PolicyCheckResult(
                checkId = "issuer_trust",
                checkName = "Issuer Trust List",
                passed = true,
                reason = "Issuer is in trust list ($name)"
            )
        } else {
            val name = if (document.issuerCertChain.certificates.isNotEmpty()) {
                document.issuerCertChain.certificates.first().subject.name
            } else {
                "Unknown"
            }
            PolicyCheckResult(
                checkId = "issuer_trust",
                checkName = "Issuer Trust List",
                passed = false,
                reason = "Issuer is not in trust list ($name)"
            )
        }
    }

    private fun checkProofVerification(verificationSucceeded: Boolean): PolicyCheckResult {
        return PolicyCheckResult(
            checkId = "proof_verification",
            checkName = "Proof/Device Response Verification",
            passed = verificationSucceeded,
            reason = if (verificationSucceeded) {
                "Device response verified successfully"
            } else {
                "Device response verification failed"
            }
        )
    }

    private fun checkAssuranceLevel(
        issuerTrusted: Boolean,
        document: MdocDocument,
    ): PolicyCheckResult {
        val namespace = DigitalPaymentCredential.CARD_NAMESPACE
        val issuerData = document.issuerNamespaces.data[namespace]
        val mandatoryClaims = listOf(
            "issuer_name",
            "masked_account_reference",
            "holder_name",
            "issue_date",
            "expiry_date",
        )
        val missingClaims = mandatoryClaims.filter { claim ->
            issuerData?.containsKey(claim) != true
        }

        return when {
            !issuerTrusted && missingClaims.isNotEmpty() -> PolicyCheckResult(
                checkId = "assurance_level",
                checkName = "Assurance Level Policy",
                passed = false,
                reason = "Issuer not trusted and missing mandatory claims: ${missingClaims.joinToString(", ")}"
            )
            !issuerTrusted -> PolicyCheckResult(
                checkId = "assurance_level",
                checkName = "Assurance Level Policy",
                passed = false,
                reason = "Issuer not trusted — cannot establish assurance level"
            )
            missingClaims.isNotEmpty() -> PolicyCheckResult(
                checkId = "assurance_level",
                checkName = "Assurance Level Policy",
                passed = false,
                reason = "Missing mandatory claims: ${missingClaims.joinToString(", ")}"
            )
            else -> PolicyCheckResult(
                checkId = "assurance_level",
                checkName = "Assurance Level Policy",
                passed = true,
                reason = "All mandatory claims present and issuer trusted"
            )
        }
    }

    private fun checkWalletBinding(verificationSucceeded: Boolean): PolicyCheckResult {
        return PolicyCheckResult(
            checkId = "wallet_binding",
            checkName = "Wallet Binding Method",
            passed = verificationSucceeded,
            reason = if (verificationSucceeded) {
                "Device-signed authentication verified"
            } else {
                "Device-signed authentication failed"
            }
        )
    }

    private fun checkCredentialExpiry(
        document: MdocDocument,
    ): PolicyCheckResult {
        val expiryDateStr = extractExpiryDate(document)
        if (expiryDateStr == null) {
            return PolicyCheckResult(
                checkId = "credential_expiry",
                checkName = "Credential Expiry Validation",
                passed = true,
                reason = "No expiry date in credential (non-expiring)"
            )
        }
        return try {
            val expiryDate = LocalDate.parse(expiryDateStr)
            val today = Clock.System.now()
                .toLocalDateTime(TimeZone.UTC).date
            if (expiryDate >= today) {
                PolicyCheckResult(
                    checkId = "credential_expiry",
                    checkName = "Credential Expiry Validation",
                    passed = true,
                    reason = "Credential expires on $expiryDate, valid"
                )
            } else {
                PolicyCheckResult(
                    checkId = "credential_expiry",
                    checkName = "Credential Expiry Validation",
                    passed = false,
                    reason = "Credential expired on $expiryDate"
                )
            }
        } catch (e: Throwable) {
            PolicyCheckResult(
                checkId = "credential_expiry",
                checkName = "Credential Expiry Validation",
                passed = false,
                reason = "Failed to parse expiry date: $expiryDateStr"
            )
        }
    }

    internal fun extractExpiryDate(document: MdocDocument): String? {
        val namespace = DigitalPaymentCredential.CARD_NAMESPACE
        val issuerData = document.issuerNamespaces.data[namespace] ?: return null
        val expiryItem = issuerData["expiry_date"] ?: return null
        return try {
            val value = expiryItem.dataElementValue
            when (value) {
                is Tagged -> (value.taggedItem as? Tstr)?.value
                is Tstr -> value.value
                else -> Cbor.toDiagnostics(
                    value,
                    setOf(DiagnosticOption.PRETTY_PRINT)
                ).trim('"')
            }
        } catch (e: Throwable) {
            null
        }
    }
}
