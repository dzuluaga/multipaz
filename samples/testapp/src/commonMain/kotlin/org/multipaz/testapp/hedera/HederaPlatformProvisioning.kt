package org.multipaz.testapp.hedera

import org.multipaz.document.DocumentStore
import org.multipaz.securearea.SecureArea

/**
 * Registers platform-specific credential implementations on the app's [DocumentStore].
 * Android registers the Hedera account credential; other platforms are a no-op.
 */
expect fun DocumentStore.Builder.addPlatformCredentialImplementations(): DocumentStore.Builder

/**
 * Provisions a Hedera account as a persistent wallet credential into [documentStore], when the
 * platform supports a hardware Hedera key (Android with KeyMint 2.0+ Curve25519). The on-chain
 * account is created lazily on first payment, so this needs no network or operator.
 *
 * @return a short status string, or null if nothing was provisioned (unsupported platform).
 */
expect suspend fun provisionPlatformHederaAccount(
    documentStore: DocumentStore,
    secureArea: SecureArea,
): String?
