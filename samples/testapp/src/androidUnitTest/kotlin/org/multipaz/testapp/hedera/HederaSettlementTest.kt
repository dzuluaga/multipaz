package org.multipaz.testapp.hedera

import com.hedera.hashgraph.sdk.PrivateKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Offline tests for the facilitator leg of settlement (the HTTP transport is injected/mocked). */
class HederaSettlementTest {
    private val config = HederaOperatorConfig(operatorId = "0.0.8903891", operatorKey = "unused-here")
    private val body = buildX402Body("AAAA==", "0.0.2222", 12_345L, config.feePayer)

    @Test
    fun `verifyAndSettle posts verify then settle and returns the transaction id`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val txId = verifyAndSettle(config, body) { url, sentBody ->
            calls.add(url to sentBody)
            when {
                url.endsWith("/verify") -> """{"isValid":true,"payer":"0.0.1111"}"""
                url.endsWith("/settle") ->
                    """{"success":true,"transactionId":"0.0.1111@1700000000.000000000","payer":"0.0.1111"}"""
                else -> error("unexpected url $url")
            }
        }
        assertEquals("0.0.1111@1700000000.000000000", txId)
        assertEquals(2, calls.size)
        assertTrue(calls[0].first.endsWith("/verify"))
        assertTrue(calls[1].first.endsWith("/settle"))
        // The exact x402 body is what's posted, unchanged, to both endpoints.
        assertEquals(body, calls[0].second)
        assertEquals(body, calls[1].second)
    }

    @Test
    fun `verifyAndSettle throws and skips settle when verify rejects`() = runBlocking {
        var settleCalled = false
        val ex = assertFailsWith<FacilitatorException> {
            verifyAndSettle(config, body) { url, _ ->
                if (url.endsWith("/settle")) settleCalled = true
                """{"isValid":false,"invalidReason":"invalid_exact_hedera_payload_amount_mismatch"}"""
            }
        }
        assertEquals("verify", ex.stage)
        assertTrue(ex.message!!.contains("amount_mismatch"))
        assertTrue(!settleCalled, "settle must not be called after verify fails")
    }

    @Test
    fun `verifyAndSettle throws when settle fails`() = runBlocking {
        val ex = assertFailsWith<FacilitatorException> {
            verifyAndSettle(config, body) { url, _ ->
                if (url.endsWith("/verify")) """{"isValid":true}"""
                else """{"success":false,"error":"INSUFFICIENT_PAYER_BALANCE"}"""
            }
        }
        assertEquals("settle", ex.stage)
        assertTrue(ex.message!!.contains("INSUFFICIENT_PAYER_BALANCE"))
    }

    @Test
    fun `hashscan url points at the testnet transaction`() {
        assertEquals(
            "https://hashscan.io/testnet/transaction/0.0.1111@1700000000.000000000",
            hashscanUrl("0.0.1111@1700000000.000000000"),
        )
    }

    @Test
    fun `buildRecoverableAccountKey is a 1-of-2 threshold of the two keys`() {
        val tee = PrivateKey.generateED25519().publicKey
        val recovery = PrivateKey.generateED25519().publicKey
        val keyList = buildRecoverableAccountKey(tee, recovery)
        assertEquals(1, keyList.threshold)
        assertEquals(2, keyList.size)
        assertTrue(keyList.toList().containsAll(listOf(tee, recovery)))
    }
}
