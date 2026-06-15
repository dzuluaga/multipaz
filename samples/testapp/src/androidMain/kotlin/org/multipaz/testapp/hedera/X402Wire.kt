package org.multipaz.testapp.hedera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * x402 v2 "exact" scheme wire body for `hedera:testnet`, matching the shape the live blocky402
 * facilitator accepted in Lab 1 (2026-06-10). Two quirks learned there and preserved: the
 * validator requires `maxTimeoutSeconds >= 1`, and the accepted requirements must be echoed
 * inside the payload or `verify` hard-500s.
 */
fun buildX402Body(
    transactionB64: String,
    payTo: String,
    amountTinybar: Long,
    feePayer: String,
    network: String = "hedera:testnet",
): String {
    val paymentRequirements = buildJsonObject {
        put("scheme", "exact")
        put("network", network)
        put("asset", "0.0.0") // native HBAR — skips HTS association entirely
        put("payTo", payTo)
        put("amount", amountTinybar.toString())
        put("maxTimeoutSeconds", 120)
        putJsonObject("extra") { put("feePayer", feePayer) }
    }
    val body = buildJsonObject {
        put("x402Version", 2)
        putJsonObject("paymentPayload") {
            put("x402Version", 2)
            put("scheme", "exact")
            put("network", network)
            put("accepted", paymentRequirements)
            putJsonObject("payload") { put("transaction", transactionB64) }
        }
        put("paymentRequirements", paymentRequirements)
    }
    return Json.encodeToString(JsonObject.serializer(), body)
}
