# Data Model: DPC Verifier Library

**Date**: 2026-03-18
**Branch**: `004-dpc-verifier`

## Entity: DPC Verification Session

Tracks the lifecycle of a single payment verification via the W3C Digital Credentials API. Created when the merchant calls `POST /presentations`, updated when the merchant's JavaScript submits the DC API response via `POST /presentations/{id}/response`, readable until TTL expiry. Results are fetchable multiple times until the session expires — deletion happens on TTL, not on read.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | String | Yes | Unguessable session ID (128+ bits of cryptographic entropy) |
| nonce | ByteArray | Yes | Random nonce for the authorization request (15 bytes) |
| encryptionKeyPair | EcKeyPair (P-256) | Yes | Ephemeral key pair for JWE response encryption/decryption |
| dcqlQuery | String | Yes | JSON-serialized DCQL query |
| dcRequest | JsonObject | Yes | The complete DC API request parameters (`digital.requests`, `mediation`) returned to the merchant and passed to `navigator.credentials.get()` |
| transactionDataBytes | List\<ByteArray\> | Yes | Exact base64url-encoded transaction data items as emitted — stored for byte-exact hash verification |
| transactionDataProfile | String | Yes | Transaction data profile type identifier (e.g., `org.multipaz.transaction_data.payment`) |
| origin | String | Yes | Expected origin of the merchant site (e.g., `https://merchant.example.com`). Used for `expected_origins` in the signed request and for `OpenID4VPDCAPIHandover` session transcript construction during verification. |
| status | SessionStatus | Yes | Current state: `requested`, `submitted`, `verified`, `failed`, `declined`, `expired` |
| result | DpcVerificationResult? | No | Populated after verification completes |
| createdAt | Instant | Yes | Session creation timestamp |
| ttl | Duration | Yes | Time-to-live (default: 5 minutes) |

**State Machine**:
```
requested → submitted → verified
                      → failed
                      → declined
requested → expired
submitted → expired
```

Note: `submitted` is a **transient internal state**. Verification runs synchronously within the `POST /presentations/{id}/response` handler (called by the merchant's JavaScript after receiving the DC API response from Chrome), so a session transitions from `requested` → `submitted` → `verified`/`failed` in a single request. The `submitted → expired` transition covers the edge case where the server crashes or restarts between receiving the response and completing verification. Externally, callers observe only `requested` (via 202), `verified`/`failed`/`declined` (via 200), or `expired` (via 404).

**Storage**: In-memory `ConcurrentHashMap` with TTL eviction. Pluggable via `SessionStorage` interface.

## Entity: Payment Request (Merchant Input)

The merchant's input containing payment details. Validated on receipt, serialized into the active transaction data profile.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| payee.name | String | Yes | Human-readable merchant/payee label |
| payee.id | String | Yes | Machine-readable payee identifier |
| amount | String | Yes | Payment amount as decimal string (e.g., "90.00") |
| currency | String | Yes | ISO 4217 currency code (e.g., "USD") |
| transactionId | String | No | Unique transaction ID. Auto-generated if omitted. |
| merchantMessage | String | No | Optional explanatory text for the consent screen |

**Validation Rules**:
- `amount` must be a positive decimal number
- `currency` must be a valid ISO 4217 code
- `payee.name` must be non-empty
- `payee.id` must be non-empty

## Entity: DPC Verification Result

The typed outcome of verification. Returned to the merchant on result retrieval.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| verified | Boolean | Yes | Whether all verification checks passed |
| credential_format | String | Yes | Format used: "mdoc" (future: "sd-jwt-vc") |
| instrument_id | String | If verified | Tokenized payment instrument identifier from DPC |
| holder_name | String | If verified | Payment account holder name from DPC |
| masked_account_ref | String | If verified | Masked account hint (e.g., "*4242") from DPC |
| issuer_name | String | If verified | Human-readable issuer name from DPC |
| txn_data_verified | Boolean | If verified | Whether transaction data hashes matched the originals |
| trust_warning | String? | No | Present if trust manager was not configured |
| error | String | If failed | Machine-readable error code (snake_case) |
| error_description | String | If failed | Human-readable error message |

**Error codes**:
- `issuer_trust_failed` — Issuer certificate chain not trusted
- `device_auth_failed` — Device authentication signature invalid
- `credential_expired` — DPC expiry_date is in the past
- `doctype_mismatch` — Credential is not `org.multipaz.payment.sca.1`
- `txn_data_mismatch` — Transaction data hashes do not match stored bytes
- `missing_instrument_id` — No payment_instrument_id in issuer-signed claims
- `user_declined` — User cancelled in the wallet
- `unsupported_format` — Credential format not supported by any registered verifier

## Entity: Session Storage Interface

Pluggable storage for sessions. Ships with in-memory implementation.

| Method | Input | Output | Description |
|--------|-------|--------|-------------|
| create | DpcSession | String (id) | Store new session, return generated ID |
| get | String (id) | DpcSession? | Retrieve session by ID, null if not found or expired |
| update | DpcSession | Unit | Update existing session |
| delete | String (id) | Unit | Remove session |

## Entity: Credential Format Verifier (SPI)

Pluggable verifier for different credential formats. Phase 1 ships with `MdocDpcVerifier`.

| Method | Input | Output | Description |
|--------|-------|--------|-------------|
| canHandle | ByteArray (decrypted response) | Boolean | Whether this verifier can process the response format |
| verify | ByteArray, DpcSession | VerificationOutcome | Run verification pipeline, return success or error |
| extractClaims | verified response | PaymentClaims | Extract payment-specific claims from verified response |

## Relationships

```
Merchant JS ──POST /presentations──→ DpcSession (created)
                                          │
                                          ├── stores dcRequest (DC API parameters)
                                          ├── stores transactionDataBytes (exact emitted bytes)
                                          ├── stores encryptionKeyPair (for JWE decrypt)
                                          ├── stores origin (for session transcript)
                                          │
                                          ▼
                               Merchant JS calls
                         navigator.credentials.get(dcRequest)
                               Chrome mediates to Wallet
                               Wallet returns response to Chrome
                               Chrome returns response to Merchant JS
                                          │
                                          ▼
Merchant JS ──POST /presentations/{id}/response──→ DpcSession (updated to submitted)
                                                        │
                                                        ├── CredentialFormatVerifier.verify()
                                                        │       │
                                                        │       ├── Verify Card (issuer sig, cert chain, expiry, doctype)
                                                        │       ├── Verify Device (key match, device auth via OpenID4VPDCAPIHandover)
                                                        │       └── Verify Transaction (hash count, alg, byte-exact values)
                                                        │
                                                        └── DpcVerificationResult (stored in session, returned synchronously)
                                                                │
Merchant JS/backend ──GET /presentations/{id}──→ DpcVerificationResult (fallback polling)
```
