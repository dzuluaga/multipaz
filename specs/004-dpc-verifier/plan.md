# Implementation Plan: DPC Verifier Library

**Branch**: `004-dpc-verifier` | **Date**: 2026-03-18 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/004-dpc-verifier/spec.md`

## Summary

Build a Ktor plugin (`multipaz-dpc-verifier`) that handles OpenID4VP verification of Digital Payment Credentials via the W3C Digital Credentials API. The library exposes three HTTP endpoints (all merchant-facing) plus a served JS SDK, manages sessions internally, and provides a Kotlin DSL builder for merchant developers. The primary flow is browser-mediated: the merchant's JavaScript calls `navigator.credentials.get()` via Chrome's DC API, and the response comes back through the browser — no wallet-facing endpoints, no QR codes. Phase 1 supports mdoc credentials with a format SPI designed for future SD-JWT VC support. The protocol is `openid4vp-v1-signed` within the DC API; TS12 is the first payment profile.

## Technical Context

**Language/Version**: Kotlin/JVM (Java 17 target)
**Primary Dependencies**: `multipaz` (core — crypto, mdoc, DCQL, OpenID4VP, trust), Ktor 3.x (Netty engine), kotlinx.serialization
**Storage**: In-memory `ConcurrentHashMap` with TTL (pluggable via `SessionStorage` interface)
**Testing**: JUnit + Ktor test host for endpoint tests, integration test with multipaz test app wallet via Chrome DC API
**Target Platform**: JVM server (Linux/macOS)
**Project Type**: Library (Ktor plugin)
**Performance Goals**: <3s verifier processing time per verification flow
**Constraints**: Must not duplicate logic from multipaz core; must reuse existing OpenID4VP, mdoc, and crypto primitives
**Scale/Scope**: Single payment credential type (DPC), single protocol flow (DC API, same-device), ~12 source files + 1 JS SDK file
**Browser Target**: Chrome 141+ with W3C Digital Credentials API support
**Security Model**: HTTPS required (secure context), CORS for browser-origin requests, capability-URL session IDs, `expected_origins` for DC API origin binding, transient user activation enforced by SDK

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Developer Simplicity | Pass | 11-line server setup + script tag + button handler. Developer never touches OpenID4VP or DC API internals. |
| II. OpenID4VP Protocol Compliance | Pass | OID4VP 1.0 + HAIP 1.0 via `openid4vp-v1-signed` protocol in DC API. Reuses `OpenID4VP.generateRequest()`, `DeviceResponse.verify()`, `JsonWebEncryption.decrypt()` from core. |
| III. Verification Correctness | Pass | Verification pipeline (steps A-E) covers DC API response validation, JWE decryption, origin binding, issuer/device auth, transaction data hash verification. |
| IV. Ktor-Native Design | Pass | `install(DpcVerifier)` plugin + `dpcVerification()` routing DSL + CORS configuration. |
| V. Reusable Foundation | Pass | Format SPI separates protocol engine from credential-specific verification. DPC logic in `MdocDpcVerifier`. |
| VI. Spec-Driven with Visual Docs | Pass | Mermaid sequence diagram, verification pipeline tables, API contract examples all in spec. |

## Project Structure

### Documentation (this feature)

```text
specs/004-dpc-verifier/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0: architecture decisions
├── data-model.md        # Phase 1: entity definitions
├── quickstart.md        # Phase 1: developer quickstart
├── contracts/
│   └── api.md           # Phase 1: HTTP API contract
└── checklists/
    └── requirements.md  # Spec quality checklist
```

### Source Code (repository root)

```text
multipaz-dpc-verifier/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── kotlin/org/multipaz/dpc/verifier/
    │   │   ├── DpcVerifierPlugin.kt         # Ktor plugin (install block, CORS config)
    │   │   ├── DpcVerificationRoute.kt      # Route DSL (dpcVerification block)
    │   │   ├── DpcSession.kt                # Session model + in-memory storage
    │   │   ├── DpcVerificationResult.kt     # Typed result
    │   │   ├── PaymentRequestBuilder.kt     # Kotlin DSL builder
    │   │   ├── CredentialFormatVerifier.kt   # Format SPI interface
    │   │   ├── MdocDpcVerifier.kt           # mdoc format verifier (Phase 1)
    │   │   └── handlers/
    │   │       ├── InitiateHandler.kt       # POST /presentations
    │   │       ├── ResponseHandler.kt       # POST /presentations/{id}/response
    │   │       └── ResultHandler.kt         # GET /presentations/{id}
    │   └── resources/
    │       └── sdk.js                       # JS SDK served at GET {path}/sdk.js
    └── test/kotlin/org/multipaz/dpc/verifier/
        ├── DpcVerifierPluginTest.kt     # Plugin installation tests
        ├── InitiateHandlerTest.kt       # Initiation endpoint tests
        ├── ResponseHandlerTest.kt       # Response submission tests
        ├── VerificationPipelineTest.kt  # Verification logic tests
        └── EndToEndTest.kt             # Full flow with test wallet via DC API
```

**Structure Decision**: New Gradle module `multipaz-dpc-verifier` in the monorepo root, following the same pattern as `multipaz-verifier-server` but with a clean, minimal structure. JVM-only (not KMP) since it's a server library.

**Key difference from cross-device flow**: No `RequestHandler` (no `GET /wallet/request.jwt/{id}`) and no wallet-facing `POST /wallet/direct_post`. The DC API request parameters are returned inline in the initiation response. The wallet response comes back through the browser, not via a wallet direct-post callback.

## Code Reuse Map

All reuse is via **direct imports from `multipaz` core** (stable public API). No code is copied from `multipaz-verifier-server` — that module serves as a reference for patterns but is not a dependency.

| New module component | Imports from multipaz core | API stability |
|---------------------|---------------------------|---------------|
| `DpcSession` (nonce, keys) | `Crypto.random()`, `EcPrivateKey.create(EcCurve.P256)` | Stable core crypto |
| `InitiateHandler` (DCQL) | `DcqlQuery`, `DcqlCredentialQuery` | Stable — used by wallet and verifier-server |
| `InitiateHandler` (txn data) | `TransactionData.parse()` | Stable — used by wallet |
| `InitiateHandler` (signed request) | `OpenID4VP.generateRequest()`, `buildJwt()` | Stable — core OpenID4VP API |
| `InitiateHandler` (DC API params) | DC API request structure built from OpenID4VP signed request | New — compose `dc_request` with `openid4vp-v1-signed` protocol |
| `ResponseHandler` (decrypt) | `JsonWebEncryption.decrypt()` | Stable core crypto |
| `ResponseHandler` (DC API handover) | `OpenID4VPDCAPIHandover` session transcript | Stable — already implemented in multipaz core |
| `MdocDpcVerifier` (parse) | `DeviceResponse.fromDataItem()` | Stable — core mdoc API |
| `MdocDpcVerifier` (verify) | `DeviceResponse.verify()` | Stable — core mdoc API |
| `MdocDpcVerifier` (claims) | `MdocDocument.issuerNamespaces`, `MdocDocument.deviceNamespaces` | Stable — core mdoc API |

**Not imported** (new code in this module): session storage, Ktor plugin/routing, CORS configuration, payment result typing, DSL builder, format SPI interface, JS SDK (`sdk.js`).

## Implementation Approach

### Part 1: Module Skeleton
- Create `multipaz-dpc-verifier/build.gradle.kts` with dependencies on `multipaz`, Ktor server, kotlinx.serialization
- Add module to `settings.gradle.kts`
- Create package structure

### Part 2: Core Infrastructure
- `DpcSession`: session model (id, nonce, ephemeral key pair, transaction data bytes, DC API request params, status, result, TTL)
- `SessionStorage` interface + `InMemorySessionStorage` with TTL eviction
- `DpcVerificationResult`: typed result (verified, credentialFormat, payment claims, error)
- `CredentialFormatVerifier`: format SPI interface (canHandle, verify, extractClaims)

### Part 3: Ktor Plugin + Route DSL
- `DpcVerifierPlugin`: Ktor `createApplicationPlugin` with config (baseUrl, trustManager, readerKey, sessionStorage, corsAllowedOrigins, apiKey, eudiCompat)
- `DpcVerificationRoute`: `dpcVerification(path)` extension function registering 3 routes + SDK static file
- `PaymentRequestBuilder`: Kotlin DSL (`paymentRequest { payee(...); amount(...) }`)
- CORS configuration for response submission endpoint (and optionally initiation endpoint)

### Part 4: Endpoint Handlers
- `InitiateHandler`: validate input, create session, build DCQL, build signed OpenID4VP request (with `expected_origins`), compose `dc_request` object (protocol `openid4vp-v1-signed` + signed request as data), store emitted transaction data bytes, return `transaction_id` + `dc_request` (201 Created)
- `ResponseHandler`: load session, validate protocol is `openid4vp-v1-signed`, validate DC API response mode, validate origin binding against `expected_origins`, decrypt JWE with session ephemeral key, build `OpenID4VPDCAPIHandover` session transcript, delegate to format SPI, store result, return verification result synchronously (200 OK)
- `ResultHandler`: load session, return 200/202/404 based on state (fallback for async architectures)

### Part 5: JS SDK
- `sdk.js`: served at `GET {path}/sdk.js`
- Exposes `multipazDpcVerify({payee, amount, currency})` function
- Pre-flight checks: secure context, DC API support (`"digital" in navigator.credentials`), user activation (`navigator.userActivation.isActive`)
- Flow: POST to `/presentations` to get `dc_request` -> call `navigator.credentials.get(dc_request)` -> extract `protocol` and `data` from `DigitalCredential` -> POST to `/presentations/{id}/response` -> return result
- Error handling: `NotAllowedError` (user cancel / no wallet), network errors, CORS errors, missing DC API support

### Part 6: mdoc Format Verifier
- `MdocDpcVerifier`: implements `CredentialFormatVerifier`
  - Parse CBOR DeviceResponse
  - Build `OpenID4VPDCAPIHandover` session transcript (not `direct_post` transcript)
  - Call `DeviceResponse.verify()` (issuer sig, device auth with DC API transcript)
  - Check credential expiry
  - Confirm doctype (`org.multipaz.payment.sca.1`)
  - Verify transaction data hashes byte-exact against stored bytes
  - Extract DPC claims (instrument_id, holder_name, masked_account_ref, issuer_name)

### Part 7: Testing + Integration
- Unit tests for each handler (using Ktor test host)
- Verification pipeline tests (valid/invalid issuer sig, tampered hashes, expired credentials, wrong protocol, origin mismatch)
- CORS tests (preflight OPTIONS, allowed/disallowed origins)
- JS SDK tests (manual or browser-based — DC API not available in headless test environments)
- End-to-end test with multipaz test app wallet via Chrome DC API

## Complexity Tracking

No constitution violations to justify. All changes are in a new module; no existing files are modified except `settings.gradle.kts` (one line to include the new module).
