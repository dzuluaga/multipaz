# Tasks: DPC Verifier Library (DC API)

**Input**: Design documents from `/specs/004-dpc-verifier/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api.md, quickstart.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1-US4)

## Phase 1: Setup

**Purpose**: Create the module skeleton and register it in the monorepo

- [ ] T001 Create `multipaz-dpc-verifier/build.gradle.kts` with dependencies on `multipaz`, `multipaz-doctypes`, Ktor server (netty, content-negotiation, cors), kotlinx.serialization, logback, and JUnit
- [ ] T002 Add `include(":multipaz-dpc-verifier")` to root `settings.gradle.kts`
- [ ] T003 Create package structure: `multipaz-dpc-verifier/src/main/kotlin/org/multipaz/dpc/verifier/` and `multipaz-dpc-verifier/src/main/kotlin/org/multipaz/dpc/verifier/handlers/`
- [ ] T004 [P] Create `multipaz-dpc-verifier/src/test/kotlin/org/multipaz/dpc/verifier/` test package structure
- [ ] T005 [P] Create `multipaz-dpc-verifier/src/main/resources/sdk.js` placeholder (empty file, implemented in US3)
- [ ] T006 Verify the module compiles with `./gradlew :multipaz-dpc-verifier:build`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that all user stories depend on

**CRITICAL**: No user story work can begin until this phase is complete

- [ ] T007 [P] Implement `DpcSession.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/DpcSession.kt` — session data class with fields: id (String, 128+ bit entropy), nonce (ByteArray), encryptionKeyPair (EcPrivateKey P-256), dcqlQuery (String), transactionDataBytes (List<ByteArray>), transactionDataProfile (String), expected_origins (List<String>), dcRequest (JsonObject — the full DC API request object returned to the merchant), status (enum: REQUESTED, SUBMITTED, VERIFIED, FAILED, DECLINED, EXPIRED), result (DpcVerificationResult?), createdAt (Instant), ttl (Duration). Include `SessionStatus` enum.
- [ ] T008 [P] Implement `SessionStorage.kt` interface and `InMemorySessionStorage.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/` — interface with create/get/update/delete methods. In-memory implementation using ConcurrentHashMap with TTL eviction (check expiry on get, background cleanup optional). Session IDs generated with `Crypto.random(16)` (128 bits) encoded as hex.
- [ ] T009 [P] Implement `DpcVerificationResult.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/DpcVerificationResult.kt` — data class with top-level fields: verified (Boolean), credentialFormat (String), doctype (String), payment (PaymentClaims?), trustWarning (String?), error (String?), errorDescription (String?). Define nested `PaymentClaims` data class with: instrumentId, holderName, maskedAccountRef, issuerName, txnDataVerified (Boolean). Annotate both with `@Serializable` using snake_case `@SerialName` annotations.
- [ ] T010 [P] Implement `CredentialFormatVerifier.kt` (format SPI) in `src/main/kotlin/org/multipaz/dpc/verifier/CredentialFormatVerifier.kt` — interface with: `fun canHandle(decryptedResponse: ByteArray): Boolean`, `fun verify(decryptedResponse: ByteArray, session: DpcSession): VerificationOutcome`. Define `VerificationOutcome` as sealed class with `Success(result: DpcVerificationResult)` and `Failure(error: String, description: String)`.
- [ ] T011 [P] Implement `PaymentRequestBuilder.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/PaymentRequestBuilder.kt` — Kotlin DSL builder: `paymentRequest { payee(name, id); amount(value, currency); merchantMessage(text); transactionId(id) }`. Produces multipaz profile JSON (`org.multipaz.transaction_data.payment` with amount as string, hash_alg as array, flat structure). Validate: amount positive decimal, currency ISO 4217, payee non-empty.

**Checkpoint**: Foundation ready — all data classes, storage, SPI, and builder in place

---

## Phase 3: User Story 1 — Merchant Initiates DPC Payment Verification (Priority: P1)

**Goal**: Merchant POSTs payment details, gets back `transaction_id` + `dc_request` (DC API parameters for `navigator.credentials.get()`)

**Independent Test**: POST to `/pay/presentations` returns valid `transaction_id` and `dc_request` with `openid4vp-v1-signed` protocol

### Implementation for User Story 1

- [ ] T012 [US1] Implement `InitiateHandler.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/handlers/InitiateHandler.kt` — handle `POST {path}/presentations`: validate `transaction_data` array (required fields, amount, currency), create DpcSession (generate nonce via `Crypto.random(15)`, ephemeral P-256 key pair via `EcPrivateKey.create(EcCurve.P256)`), build DCQL query for `org.multipaz.payment.sca.1` using `DcqlQuery`/`DcqlCredentialQuery`, build signed OpenID4VP request with `expected_origins` from config, store exact transaction data bytes, build the full `dc_request` object (with `digital.requests[{protocol: "openid4vp-v1-signed", data: ...}]` and `mediation: "required"`), save session, return 201 with `{transaction_id, dc_request}`.
- [ ] T013 [US1] Implement `DpcVerifierPlugin.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/DpcVerifierPlugin.kt` — Ktor `createApplicationPlugin` with config class: `baseUrl` (String, required), `expectedOrigins` (List<String>, derived from baseUrl if not set), `trustManager` (TrustManagerInterface?, optional), `readerKey` (AsymmetricKey?, optional — generate self-signed ephemeral with warning if not provided), `sessionStorage` (SessionStorage, default InMemorySessionStorage), `eudiCompat` (Boolean, default false), `sessionTtl` (Duration, default 5 minutes), `corsAllowedOrigins` (List<String>?, optional — for CORS configuration).
- [ ] T014 [US1] Implement `DpcVerificationRoute.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/DpcVerificationRoute.kt` — `fun Route.dpcVerification(path: String)` extension function that registers all three endpoints + SDK under `route(path)`. Wire up `InitiateHandler` for `POST /presentations`. Install CORS for the route using `corsAllowedOrigins` from config. Serve `sdk.js` from resources at `GET {path}/sdk.js`. Stub the other two handlers (return 501 Not Implemented) to be filled in US2 and US3.
- [ ] T015 [US1] Write test `InitiateHandlerTest.kt` in `src/test/kotlin/org/multipaz/dpc/verifier/InitiateHandlerTest.kt` — using Ktor test host: valid initiation returns 201 with `transaction_id` and `dc_request` containing `digital.requests[0].protocol == "openid4vp-v1-signed"`; missing amount returns 400 `missing_field`; invalid currency returns 400 `invalid_currency`; negative amount returns 400 `invalid_amount`.

**Checkpoint**: Merchant can initiate a payment verification and get DC API request parameters

---

## Phase 4: User Story 2 — DC API Response Submission + Verification (Priority: P1)

**Goal**: Merchant JS submits the DC API response (`{protocol, data}`) from `navigator.credentials.get()`, verifier decrypts and runs full verification pipeline

**Independent Test**: Submit a pre-built encrypted mdoc DeviceResponse via `POST /presentations/{id}/response` and confirm verification succeeds or fails correctly

### Implementation for User Story 2

- [ ] T016 [US2] Implement `MdocDpcVerifier.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/MdocDpcVerifier.kt` — implements `CredentialFormatVerifier`. Full verification pipeline steps A-E: parse CBOR DeviceResponse via `DeviceResponse.fromDataItem()`, build session transcript using `OpenID4VPDCAPIHandover` (not `OpenID4VPHandover`), call `DeviceResponse.verify(sessionTranscript)` for issuer sig + device auth (steps B1-B2, C1-C2), check `expiry_date >= now` (B3), confirm doctype `org.multipaz.payment.sca.1` (B4), check `payment_instrument_id` presence (B5), extract transaction data hashes from deviceNamespaces and compare byte-exact against session's stored bytes (D1-D3), extract DPC claims (E1-E2). Return `VerificationOutcome.Success` or `Failure` with specific error code.
- [ ] T017 [US2] Implement `ResponseHandler.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/handlers/ResponseHandler.kt` — handle `POST {path}/presentations/{id}/response`: parse JSON body with `protocol` and `data` fields. Validate: (A1) match `id` to session, verify session state is REQUESTED; (A2) validate `protocol` is `openid4vp-v1-signed`; (A3) validate this is a DC API response (not `direct_post`); (A4) validate origin binding — verify the origin in the DC API handover matches `expected_origins` from the session. Set session to SUBMITTED, decrypt JWE from `data` via `JsonWebEncryption.decrypt()` using session's ephemeral private key (A5), detect format (A6), iterate registered `CredentialFormatVerifier` instances (call `canHandle`, then `verify`), store result, set terminal state, return 200 with `DpcVerificationResult` synchronously. Handle 409 if already submitted, 404 if session not found, 400 if wrong state or decryption fails. If `data` contains an error (user declined): set session status to DECLINED, return 200 with full `DpcVerificationResult` (`verified: false, credential_format: "mdoc", doctype: "org.multipaz.payment.sca.1", error: "user_declined", error_description: "User cancelled the payment in the wallet"`).
- [ ] T018 [US2] Wire `ResponseHandler` into `DpcVerificationRoute.kt` — replace the stub for `POST /presentations/{id}/response` with the real handler. Register `MdocDpcVerifier` as the default format verifier in the plugin.
- [ ] T019 [US2] Write test `VerificationPipelineTest.kt` in `src/test/kotlin/org/multipaz/dpc/verifier/VerificationPipelineTest.kt` — test MdocDpcVerifier with: valid DeviceResponse passes all checks; invalid issuer signature returns `issuer_trust_failed`; expired credential returns `credential_expired`; wrong doctype returns `doctype_mismatch`; tampered transaction data hashes return `txn_data_mismatch`. Test ResponseHandler with: valid submission returns 200 with verified result; wrong protocol returns 400; duplicate submission returns 409.

**Checkpoint**: Full DC API response submission and verification pipeline works

---

## Phase 5: User Story 3 — Result Polling + JS SDK (Priority: P1)

**Goal**: Merchant can poll for results via GET endpoint; JS SDK handles the full DC API ceremony automatically

**Independent Test**: GET /presentations/{id} returns correct 200/202/404 for each session state; sdk.js is served and contains the `multipazDpcVerify()` function

### Implementation for User Story 3

- [ ] T020 [US3] Implement `ResultHandler.kt` in `src/main/kotlin/org/multipaz/dpc/verifier/handlers/ResultHandler.kt` — handle `GET {path}/presentations/{id}`: load session from storage. If not found or expired: return 404. If status is REQUESTED or SUBMITTED: return 202 Accepted with `Retry-After: 2` header and `{"status": "pending"}` body (or 400 with `presentation_pending` error if `eudiCompat` is true). If status is VERIFIED/FAILED/DECLINED: return 200 with the `DpcVerificationResult` serialized as JSON (snake_case). Include `trust_warning` field if trust manager was not configured.
- [ ] T021 [US3] Wire `ResultHandler` into `DpcVerificationRoute.kt` — replace the stub for `GET /presentations/{id}`.
- [ ] T022 [US3] Implement `sdk.js` in `src/main/resources/sdk.js` — JS SDK exposing `multipazDpcVerify({payee, amount, currency})`. The SDK must: (a) check secure context (`window.isSecureContext`) and throw on non-HTTPS origins (except localhost); (b) check `navigator.userActivation.isActive` and throw if called outside a user gesture; (c) check DC API support (`"digital" in navigator.credentials`) and throw a clear error; (d) POST to `{basePath}/presentations` with transaction data; (e) call `navigator.credentials.get(dc_request)` with the returned `dc_request`; (f) extract `protocol` and `data` from the `DigitalCredential` response; (g) POST to `{basePath}/presentations/{id}/response` with `{protocol, data}`; (h) return the verification result. Handle `NotAllowedError` (user cancel / no wallet) gracefully. Derive `basePath` from the script tag's `src` attribute (strip `/sdk.js`).
- [ ] T023 [US3] Write test `ResultHandlerTest.kt` in `src/test/kotlin/org/multipaz/dpc/verifier/ResultHandlerTest.kt` — test: verified session returns 200 with payment claims; pending session returns 202 with Retry-After; pending session with eudiCompat returns 400; expired session returns 404; declined session returns 200 with full result including `credential_format`, `doctype`, and `user_declined` error. Test that `GET {path}/sdk.js` returns JavaScript content with correct Content-Type.

**Checkpoint**: Full end-to-end flow works — merchant initiates, SDK handles DC API ceremony, merchant gets result

---

## Phase 6: User Story 4 — Developer Experience (Priority: P1)

**Goal**: Prove the "under 20 lines" backend + "1 script tag + button handler" frontend promise

**Independent Test**: Start server with minimal config, confirm all 3 endpoints + SDK respond

### Implementation for User Story 4

- [ ] T024 [US4] Write integration test `DpcVerifierPluginTest.kt` in `src/test/kotlin/org/multipaz/dpc/verifier/DpcVerifierPluginTest.kt` — using Ktor test host: install plugin with minimal config (just `baseUrl`), register `dpcVerification("/pay")`, verify all 3 endpoints + SDK are reachable (POST /pay/presentations returns 201 or 400, GET /pay/presentations/unknown returns 404, POST /pay/presentations/unknown/response returns 404, GET /pay/sdk.js returns JavaScript).
- [ ] T025 [US4] Write integration test `EndToEndTest.kt` in `src/test/kotlin/org/multipaz/dpc/verifier/EndToEndTest.kt` — full flow: initiate with valid payment request via POST /presentations, verify session created with `transaction_id` and `dc_request`, (mock or construct a valid mdoc DeviceResponse using multipaz core test utilities), submit to POST /presentations/{id}/response with `{protocol, data}`, verify synchronous 200 response with `verified: true` and correct DPC claims. Also test polling via GET /presentations/{id}. This validates SC-001 through SC-005.
- [ ] T026 [US4] Verify the quickstart code from `quickstart.md` compiles — create a minimal `main()` matching the quickstart and confirm it starts the server with all 3 endpoints + SDK registered. This validates the "under 20 lines" promise (SC-001).

**Checkpoint**: Developer experience validated — library works as documented

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Final cleanup before PR

- [ ] T027 [P] Run detekt/ktlint on the new module and fix any issues
- [ ] T028 [P] Verify all `@Serializable` classes produce snake_case JSON matching the API contract in `contracts/api.md`
- [ ] T029 [P] Add KDoc comments to the 3 public API surface classes: `DpcVerifier` (plugin), `dpcVerification()` (route DSL), `paymentRequest {}` (builder)
- [ ] T030 [P] Verify CORS configuration works: preflight OPTIONS request to `/presentations/{id}/response` returns correct headers
- [ ] T031 Run full test suite: `./gradlew :multipaz-dpc-verifier:test`
- [ ] T032 Verify no existing multipaz tests are broken: `./gradlew test` (full monorepo)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 (module must compile)
- **US1 (Phase 3)**: Depends on Phase 2 (needs DpcSession, SessionStorage, PaymentRequestBuilder)
- **US2 (Phase 4)**: Depends on US1 (needs InitiateHandler to create sessions) + Phase 2 (needs CredentialFormatVerifier SPI)
- **US3 (Phase 5)**: Depends on US2 (needs sessions with results stored) — SDK and ResultHandler can be built in parallel
- **US4 (Phase 6)**: Depends on US1+US2+US3 (all endpoints + SDK must work)
- **Polish (Phase 7)**: Depends on US4

### Within Each Phase

- Tasks marked [P] can run in parallel
- Models/data classes before handlers
- Handlers before route wiring
- Route wiring before tests

### Parallel Opportunities

**Phase 2** (all [P] — independent data classes):
```
T007 DpcSession.kt + T008 SessionStorage.kt + T009 DpcVerificationResult.kt + T010 CredentialFormatVerifier.kt + T011 PaymentRequestBuilder.kt
```

**Phase 5** (partial parallel):
```
T020 ResultHandler.kt + T022 sdk.js (independent files, no dependencies on each other)
```

**Phase 7** (all [P] — independent checks):
```
T027 detekt + T028 serialization check + T029 KDoc + T030 CORS check
```

---

## Implementation Strategy

### MVP First (Phases 1-3 = Initiation Only)

1. Setup + Foundational -> module compiles
2. US1 -> merchant can initiate and get `transaction_id` + `dc_request`
3. **STOP and VALIDATE**: POST works, session is created, `dc_request` has correct DC API shape

### Core Flow (Phases 4-5 = Verification + Result + SDK)

4. US2 -> DC API response submission, decryption, full verification pipeline with `OpenID4VPDCAPIHandover` transcript
5. US3 -> result polling + JS SDK that handles the full DC API ceremony
6. **STOP and VALIDATE**: Full end-to-end flow works (SDK -> initiate -> DC API -> submit -> verify -> result)

### Validation (Phase 6 = Developer Experience)

7. US4 -> integration tests + quickstart validation
8. **STOP and VALIDATE**: Library works as documented

### Cleanup (Phase 7 = Polish)

9. Lint, serialization check, docs, CORS verification, full test suite

---

## Key Differences from Previous (Pre-DC API) Tasks

| Aspect | Previous | DC API Pivot |
|--------|----------|--------------|
| Endpoints | 4 (including wallet-facing) | 3 merchant-facing + SDK |
| Wallet transport | `GET /wallet/request.jwt/{id}` + `POST /wallet/direct_post` | Browser-mediated via `navigator.credentials.get()` |
| Response submission | Wallet POSTs to `direct_post` (form-encoded) | Merchant JS POSTs to `/presentations/{id}/response` (JSON with `{protocol, data}`) |
| Initiation response | `{transaction_id, client_id, request_uri}` | `{transaction_id, dc_request}` (ready for `navigator.credentials.get()`) |
| Session transcript | `OpenID4VPHandover` | `OpenID4VPDCAPIHandover` |
| Origin binding | N/A | `expected_origins` in signed request, verified in handover |
| JS SDK | N/A | `sdk.js` served at `GET {path}/sdk.js` — handles full ceremony |
| CORS | N/A | Required for browser-to-backend communication |
| User activation | N/A | SDK checks `navigator.userActivation.isActive` |
| Removed files | — | `handlers/RequestHandler.kt` (JAR serving), old `ResponseHandler.kt` (direct_post) |
| New files | — | `sdk.js`, updated `ResponseHandler.kt` (DC API response) |
