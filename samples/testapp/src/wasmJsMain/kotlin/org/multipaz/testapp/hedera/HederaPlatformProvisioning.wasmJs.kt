package org.multipaz.testapp.hedera

import org.multipaz.document.DocumentStore
import org.multipaz.securearea.SecureArea

// The Hedera x402 demo is Android-only (the Hedera SDK is JVM/Android).
actual fun DocumentStore.Builder.addPlatformCredentialImplementations(): DocumentStore.Builder = this

actual suspend fun provisionPlatformHederaAccount(
    documentStore: DocumentStore,
    secureArea: SecureArea,
): String? = null
