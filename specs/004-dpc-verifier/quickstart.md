# Quickstart: DPC Verifier Library

**Date**: 2026-03-18
**Branch**: `004-dpc-verifier`

## 1. Add the dependency

In your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":multipaz-dpc-verifier"))
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
}
```

## 2. Create a verifier server (11 lines of Kotlin)

```kotlin
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.routing.*
import org.multipaz.dpc.verifier.*

fun main() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        install(DpcVerifier) {
            baseUrl = "http://localhost:8080"
        }

        routing {
            dpcVerification("/pay")
        }
    }.start(wait = true)
}
```

That's it. Three endpoints plus a JS SDK are live:
- `POST /pay/presentations` -- initiate a payment verification (201 Created)
- `POST /pay/presentations/{id}/response` -- submit DC API response for verification (200)
- `GET /pay/presentations/{id}` -- poll for result (200/202/404, fallback for async architectures)
- `GET /pay/sdk.js` -- JS SDK served automatically for frontend integration

**Note on the reader signing key**: HAIP 1.0 mandates signed authorization requests. For local testing, the library generates a self-signed ephemeral key and logs a warning. For production, configure a proper `readerKey`:

```kotlin
install(DpcVerifier) {
    baseUrl = "https://merchant.example.com"
    readerKey = loadKey("keys/verifier.pem")
    trustManager = TrustManager(loadCerts("certs/issuer-root.pem"))
}
```

## 3. Create a checkout page (1 script tag + button handler)

Create an `index.html` and serve it however you like (Ktor static files, a separate server, or just open it from localhost):

```html
<!DOCTYPE html>
<html>
<head><title>Checkout</title></head>
<body>
  <h1>Checkout</h1>
  <p>Total: $90.00</p>
  <button onclick="handlePayment()">Pay $90.00</button>
  <p id="status"></p>

  <script src="/pay/sdk.js"></script>
  <script>
    async function handlePayment() {
      // MUST be called from a user gesture (click/tap) —
      // navigator.credentials.get() requires transient user activation
      try {
        const result = await multipazDpcVerify({
          payee: { name: "Delta Airlines", id: "merchant-delta-001" },
          amount: "90.00",
          currency: "USD"
        });
        if (result.verified) {
          document.getElementById("status").textContent =
            "Payment approved! Instrument: " + result.payment.instrument_id;
        } else {
          document.getElementById("status").textContent =
            "Payment failed: " + result.error_description;
        }
      } catch (e) {
        document.getElementById("status").textContent = "Error: " + e.message;
      }
    }
  </script>
</body>
</html>
```

The SDK (`sdk.js`) is served automatically by the verifier. It handles the entire DC API ceremony:

1. POSTs to `/pay/presentations` to get the DC API request parameters
2. Calls `navigator.credentials.get()` with those parameters -- Chrome mediates to the wallet
3. POSTs the wallet's response to `/pay/presentations/{id}/response`
4. Returns the verified result synchronously -- no polling needed

**Important**: `multipazDpcVerify()` must be called from a user gesture handler (click, tap). The W3C Digital Credentials API requires transient user activation. The SDK checks `navigator.userActivation.isActive` and throws a clear error if called outside a gesture.

## 4. HTTPS requirements

The DC API requires a **secure context** (HTTPS). Chrome makes an exception for `localhost`, so local development works without certificates. For any other origin, you must serve over HTTPS.

- **Local development**: `baseUrl = "http://localhost:8080"` -- works as-is, no tunnel needed
- **Production**: `baseUrl = "https://merchant.example.com"` -- HTTPS required

## 5. End-to-end test with the multipaz wallet

To test the full flow on a single device (no tunnel or QR code needed):

1. Start the verifier server (step 2 above)
2. Serve the checkout page from the same server (or any `localhost` origin)
3. Run the multipaz test app (branch `002-dpc-transaction-auth`) on an Android device or emulator
4. Provision a DPC in the test app
5. Open Chrome on the Android device and navigate to `http://localhost:8080` (use `adb reverse tcp:8080 tcp:8080` if running the server on your dev machine)
6. Click "Pay $90.00"
7. Chrome shows the wallet picker with the provisioned DPC
8. Approve with biometric -- the wallet signs the response with transaction data binding
9. The page shows "Payment approved!"

No ngrok or tunnel is needed. The DC API is browser-mediated, so the wallet communicates with Chrome directly on the device. `localhost` is a secure context for development purposes.

## 6. Polling fallback (server-to-server)

The primary flow returns the verification result synchronously via the SDK. If you need a server-to-server polling pattern instead (e.g., your backend initiates the verification and needs the result), use the `GET /pay/presentations/{id}` endpoint:

```kotlin
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

val client = HttpClient()

runBlocking {
    // 1. Initiate the verification
    val response = client.post("http://localhost:8080/pay/presentations") {
        contentType(ContentType.Application.Json)
        setBody("""
            {
              "transaction_data": [{
                "type": "org.multipaz.transaction_data.payment",
                "credential_ids": ["payment_cred"],
                "transaction_data_hashes_alg": ["sha-256"],
                "payee": {"name": "Delta Airlines", "id": "merchant-delta-001"},
                "currency": "USD",
                "amount": "90.00"
              }]
            }
        """.trimIndent())
    }
    val session = response.body<InitiateResponse>()

    // 2. (The frontend handles the DC API ceremony via the SDK)

    // 3. Poll for the result
    while (true) {
        val result = client.get(
            "http://localhost:8080/pay/presentations/${session.transactionId}"
        )

        when (result.status) {
            HttpStatusCode.OK -> {
                val verification = result.body<VerificationResult>()
                if (verification.verified) {
                    println("Payment approved: ${verification.payment.instrumentId}")
                } else {
                    println("Failed: ${verification.errorDescription}")
                }
                break
            }
            HttpStatusCode.Accepted -> {
                val retryAfter = result.headers["Retry-After"]?.toLong() ?: 2
                delay(retryAfter * 1000)
            }
            HttpStatusCode.NotFound -> {
                println("Session expired")
                break
            }
        }
    }
}
```

## What happens under the hood

You don't need to know any of this, but here's what the library handles:

1. **Initiation**: Generates a nonce, ephemeral P-256 key pair, DCQL query for DPC, and base64url-encoded transaction data. Builds the OpenID4VP DC API request with `openid4vp-v1-signed` protocol and `expected_origins` for origin binding. Stores the exact emitted bytes for later hash verification.
2. **DC API ceremony**: The SDK calls `navigator.credentials.get()` with the DC API request parameters. Chrome mediates to the wallet app registered as an Android CredentialManager provider.
3. **Response verification**: Decrypts the JWE using the session's ephemeral private key, parses the mdoc DeviceResponse, runs the full verification pipeline (issuer signature, certificate chain, device authentication via `OpenID4VPDCAPIHandover` session transcript, transaction data hash verification against stored bytes), and returns the result synchronously.
4. **Result delivery**: Returns the typed verification result with payment instrument details, holder name, and transaction data verification status.
