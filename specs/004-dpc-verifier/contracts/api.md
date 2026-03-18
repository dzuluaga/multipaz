# API Contract: DPC Verifier Endpoints

**Date**: 2026-03-18
**Branch**: `004-dpc-verifier`

## Wire Format Rule

Phase 1 uses the **multipaz transaction data profile** (`org.multipaz.transaction_data.payment`) on the wire. This is the only profile supported until the format SPI adds TS12 (`urn:eudi:sca:payment:1`) with SD-JWT VC support. The Kotlin DSL builder produces the multipaz profile. All examples in this contract use the multipaz profile.

## Naming Conventions

- All JSON field names use **snake_case** (e.g., `transaction_id`, `instrument_id`, `error_description`)
- All error codes use **snake_case** (e.g., `missing_field`, `presentation_not_found`, `already_submitted`)
- Kotlin data class properties use **camelCase** per Kotlin convention (mapped to snake_case on serialization)

## Endpoints Overview

| Method | Path | Audience | Purpose |
|--------|------|----------|---------|
| POST | `{path}/presentations` | Merchant backend/JS | Initiate verification, return DC API request parameters |
| POST | `{path}/presentations/{id}/response` | Merchant JS (via SDK) | Submit DC API response for verification |
| GET | `{path}/presentations/{id}` | Merchant backend | Poll for result (fallback for async architectures) |
| GET | `{path}/sdk.js` | Merchant frontend | JS SDK that handles the DC API ceremony |

No wallet-facing endpoints exist. The W3C Digital Credentials API mediates wallet interaction through the browser; the verifier never communicates with the wallet directly.

## CORS

The response submission endpoint (`POST /presentations/{id}/response`) MUST support CORS with configurable allowed origins and proper preflight (`OPTIONS`) handling. The initiation endpoint (`POST /presentations`) SHOULD also support CORS if the SDK calls it directly from the browser. The SDK serves from the same origin, so `GET /sdk.js` does not require CORS.

---

## POST `{path}/presentations` -- Initiate Verification

### Request

**Content-Type**: `application/json`

**Body**:
```json
{
  "transaction_data": [
    {
      "type": "org.multipaz.transaction_data.payment",
      "credential_ids": ["payment_cred"],
      "transaction_data_hashes_alg": ["sha-256"],
      "transaction_id": "txn-abc-123",
      "payee": {
        "name": "Delta Airlines",
        "id": "merchant-delta-001"
      },
      "currency": "USD",
      "amount": "90.00",
      "merchant_message": "Flight NYC-LAX, Mar 15"
    }
  ]
}
```

**Required fields**: `type`, `credential_ids`, `transaction_data_hashes_alg`, `payee.name`, `payee.id`, `currency`, `amount`
**Optional fields**: `transaction_id` (auto-generated if omitted), `merchant_message`

### Response (201 Created)

**Content-Type**: `application/json`

```json
{
  "transaction_id": "a1b2c3d4e5f6...",
  "dc_request": {
    "digital": {
      "requests": [
        {
          "protocol": "openid4vp-v1-signed",
          "data": "eyJ..."
        }
      ]
    },
    "mediation": "required"
  }
}
```

The `dc_request` object is passed directly to `navigator.credentials.get()` per the W3C Digital Credentials API spec. The `requests` array uses the standard `DigitalCredentialRequestOptions` shape with `protocol` + `data` fields. The signed OpenID4VP request in `data` includes `expected_origins` for origin binding. The SDK handles this automatically.

Session IDs (`transaction_id`) have 128+ bits of cryptographic entropy. Knowledge of the session ID is the authentication mechanism for result retrieval (capability-URL pattern). This is sufficient for a POC; production deployments should add API key or mTLS authentication.

### Error Responses

| Status | Error | Description |
|--------|-------|-------------|
| 400 | `missing_field` | Required field missing (`error_description` names the field) |
| 400 | `invalid_amount` | Amount is zero, negative, or not a valid decimal |
| 400 | `invalid_currency` | Currency code is not ISO 4217 |
| 400 | `invalid_transaction_data` | `transaction_data` array is empty or malformed |

---

## POST `{path}/presentations/{id}/response` -- Submit DC API Response

The merchant's JS submits the `DigitalCredential` object returned by `navigator.credentials.get()`. The SDK extracts `protocol` and `data` from the credential and posts them.

### Request

**Content-Type**: `application/json`

**Body**:
```json
{
  "protocol": "openid4vp-v1-signed",
  "data": "eyJ..."
}
```

These fields map directly to the `DigitalCredential` interface: `credential.protocol` and `credential.data`. The `data` field contains the JWE-encrypted OpenID4VP response.

### Response (200 OK -- Verified)

```json
{
  "verified": true,
  "credential_format": "mdoc",
  "payment": {
    "instrument_id": "tok-abc-123",
    "holder_name": "Jane Doe",
    "masked_account_ref": "*4242",
    "issuer_name": "SuperBank",
    "txn_data_verified": true
  }
}
```

If the trust manager was not configured, the response includes an additional field:
```json
{
  "verified": true,
  "trust_warning": "Issuer trust was not validated -- no trust manager configured",
  ...
}
```

### Response (200 OK -- Failed)

```json
{
  "verified": false,
  "error": "issuer_trust_failed",
  "error_description": "Issuer certificate chain not trusted"
}
```

### Response (200 OK -- Declined)

```json
{
  "verified": false,
  "error": "user_declined",
  "error_description": "User cancelled the payment in the wallet"
}
```

### Error Responses

| Status | Error | Condition |
|--------|-------|-----------|
| 400 | `invalid_protocol` | `protocol` field missing or not `openid4vp-v1-signed` |
| 400 | `missing_field` | `data` field missing |
| 400 | `invalid_encrypted_response` | JWE decryption failed |
| 404 | `presentation_not_found` | Session not found or expired |
| 409 | `already_submitted` | A response has already been accepted for this session |

---

## GET `{path}/presentations/{id}` -- Get Verification Result (Fallback)

Returns the same verification result format as `POST /presentations/{id}/response`. This endpoint exists as a fallback for async architectures that do not use the synchronous response from the submission endpoint.

### Response (200 OK -- Result Available)

Same body as the `POST /presentations/{id}/response` success responses (verified, failed, or declined).

### Response (202 Accepted -- Pending)

**Headers**: `Retry-After: 2` (seconds)

```json
{
  "status": "pending"
}
```

### Status Code Summary

| Status | Condition |
|--------|-----------|
| 200 | Result available (verified, failed, or declined) |
| 202 | Response has not been submitted yet -- poll again after `Retry-After` seconds |
| 404 | Transaction not found or expired |

---

## GET `{path}/sdk.js` -- JS SDK

Serves a static JavaScript file that handles the full DC API ceremony. The SDK exposes a single function:

```javascript
const result = await multipazDpcVerify({
  payee: { name: "Delta Airlines", id: "merchant-delta-001" },
  amount: "90.00",
  currency: "USD"
});
```

The SDK performs the following steps:
1. `POST /presentations` with transaction data
2. Call `navigator.credentials.get(dc_request)` using the returned `dc_request`
3. `POST /presentations/{id}/response` with `{protocol, data}` from the `DigitalCredential`
4. Return the verification result to the caller

The SDK MUST:
- Detect missing DC API support (`"digital" in navigator.credentials`) and throw a clear error
- Check `navigator.userActivation.isActive` and throw if called outside a user gesture
- Check secure context and throw on non-HTTPS origins (except `localhost`)
- Handle `NotAllowedError` (user cancel / no wallet) gracefully

---

## Common Error Response Format

All error responses use this format:

```json
{
  "error": "error_code_here",
  "error_description": "Human-readable description of what went wrong"
}
```

Both fields are always present on error responses.
