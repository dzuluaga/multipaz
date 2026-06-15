package org.multipaz.testapp.hedera

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.Algorithm
import org.multipaz.document.DocumentStore
import org.multipaz.securearea.AndroidKeystoreCreateKeySettings
import org.multipaz.securearea.AndroidKeystoreSecureArea
import org.multipaz.securearea.SecureArea
import org.multipaz.securearea.UserAuthenticationType
import kotlin.time.Duration.Companion.seconds

actual fun DocumentStore.Builder.addPlatformCredentialImplementations(): DocumentStore.Builder =
    addCredentialImplementation(HederaAccountCredential.CREDENTIAL_TYPE) { document ->
        HederaAccountCredential(document)
    }

actual suspend fun provisionPlatformHederaAccount(
    documentStore: DocumentStore,
    secureArea: SecureArea,
): String? {
    if (secureArea !is AndroidKeystoreSecureArea) return null
    if (!AndroidKeystoreSecureArea.Capabilities().curve25519Supported) {
        return "Hedera account skipped: device KeyMint lacks Curve25519 (needs KeyMint 2.0+)."
    }
    val document = documentStore.createDocument(
        displayName = "Hedera Account",
        typeDisplayName = "Hedera testnet account",
    )
    HederaAccountCredential.create(
        document = document,
        domain = "hedera",
        secureArea = secureArea,
        network = "hedera:testnet",
        accountId = null, // on-chain account is created lazily on first payment
        createKeySettings = AndroidKeystoreCreateKeySettings.Builder(ByteString())
            .setAlgorithm(Algorithm.ED25519)
            .setUserAuthenticationRequired(true, 0.seconds, setOf(UserAuthenticationType.BIOMETRIC))
            .build(),
    )
    return "Hedera Account provisioned (Ed25519 key in the TEE)."
}
