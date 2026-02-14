package org.multipaz.documenttype.knowntypes

import org.multipaz.cbor.toDataItem
import org.multipaz.cbor.toDataItemFullDate
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentType
import org.multipaz.documenttype.Icon
import kotlinx.datetime.LocalDate

/**
 * Payment SCA credential profile for card-based digital payments.
 */
object DigitalPaymentCredential {
    const val CARD_DOCTYPE = "org.multipaz.payment.sca.1"
    const val CARD_NAMESPACE = "org.multipaz.payment.sca.1"

    fun getDocumentType(): DocumentType {
        return DocumentType.Builder("Payment Card Credential")
            .addMdocDocumentType(CARD_DOCTYPE)

            .addMdocAttribute(
                DocumentAttributeType.String,
                "credential_id",
                "Credential ID",
                "Identifier for this payment SCA credential.",
                true,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "cred-01A2B3C4".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "issuer_id",
                "Issuer ID",
                "Identifier of the issuer for this credential.",
                true,
                CARD_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                "issuer-bank-utopia".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "issuer_name",
                "Issuer Name",
                "Human-readable issuer name.",
                true,
                CARD_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                "Utopia Bank".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "payment_instrument_id",
                "Payment Instrument ID",
                "Tokenized payment instrument identifier.",
                false,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "pi-77AABBCC".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "payment_instrument_type",
                "Payment Instrument Type",
                "Type of payment instrument, for example card or account.",
                true,
                CARD_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                "card".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "network",
                "Network",
                "Payment network or scheme.",
                true,
                CARD_NAMESPACE,
                Icon.ACCOUNT_BALANCE,
                "visa".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "masked_account_reference",
                "Masked Account Reference",
                "Masked account reference, for example PAN last 4.",
                true,
                CARD_NAMESPACE,
                Icon.NUMBERS,
                "****1234".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "holder_name",
                "Holder Name",
                "Payment account holder name.",
                true,
                CARD_NAMESPACE,
                Icon.PERSON,
                "${SampleData.GIVEN_NAME} ${SampleData.FAMILY_NAME}".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "wallet_binding_method",
                "Wallet Binding Method",
                "Method used to bind the credential to the wallet or device.",
                true,
                CARD_NAMESPACE,
                Icon.FINGERPRINT,
                "device_key".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.String,
                "assurance_level",
                "Assurance Level",
                "Declared credential assurance level.",
                true,
                CARD_NAMESPACE,
                Icon.MILITARY_TECH,
                "high".toDataItem()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "issue_date",
                "Issue Date",
                "Date when this credential was issued.",
                true,
                CARD_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.ISSUE_DATE).toDataItemFullDate()
            )
            .addMdocAttribute(
                DocumentAttributeType.Date,
                "expiry_date",
                "Expiry Date",
                "Date when this credential expires.",
                true,
                CARD_NAMESPACE,
                Icon.CALENDAR_CLOCK,
                LocalDate.parse(SampleData.EXPIRY_DATE).toDataItemFullDate()
            )

            .addSampleRequest(
                id = "payment_sca_minimal",
                displayName = "Payment SCA (Minimal)",
                mdocDataElements = mapOf(
                    CARD_NAMESPACE to mapOf(
                        "credential_id" to false,
                        "issuer_name" to false,
                        "network" to false,
                        "masked_account_reference" to false,
                        "holder_name" to false,
                        "wallet_binding_method" to false,
                        "assurance_level" to false,
                        "expiry_date" to false
                    )
                )
            )
            .addSampleRequest(
                id = "payment_sca_full",
                displayName = "Payment SCA (All Data Elements)",
                mdocDataElements = mapOf(
                    CARD_NAMESPACE to mapOf()
                )
            )
            .build()
    }
}
