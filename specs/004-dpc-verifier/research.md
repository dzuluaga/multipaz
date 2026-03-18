# Research: DPC Verifier Library

**Date**: 2026-03-17
**Branch**: `004-dpc-verifier`

## Decision 1: Architecture Center

**Decision**: OpenID4VP 1.0 + HAIP 1.0 is the core protocol engine. TS12 is the first pluggable payment profile.

**Rationale**: The constitution (Principle V) requires a credential-type-agnostic protocol engine. OpenID4VP is the stable transport layer across both mdoc and SD-JWT VC. TS12 is a payment-specific rulebook that sits on top. Building around TS12 would couple payment semantics into the protocol engine and require rewriting when a non-payment credential type is added.

**Alternatives considered**:
- TS12-first: Rejected — violates constitution Principle V (Reusable Foundation)
- EMV 3DS as transport: Rejected — 3DS is the payment rail, not the credential verification protocol. The verifier produces the SCA proof; 3DS carries it to the issuer.

## Decision 2: Credential Format Scope

**Decision**: Ship mdoc first with a format SPI (Service Provider Interface) from day one. Add SD-JWT VC when test vectors exist.

**Rationale**: The multipaz wallet only produces mdoc today. Implementing SD-JWT VC without a wallet to test against produces unvalidatable code. The format SPI boundary (decrypt → detect format → delegate → return normalized result) is cheap to design and ensures the addition is mechanical later.

**Alternatives considered**:
- Both formats from day one: Rejected — doubles parsing, trust, and test complexity without improving POC viability
- SD-JWT VC only: Rejected — no test wallet available; blocks end-to-end validation

## Decision 3: Merchant Request Body

**Decision**: Phase 1 uses the multipaz transaction data profile (`org.multipaz.transaction_data.payment`) on the HTTP wire. TS12 profile (`urn:eudi:sca:payment:1`) arrives with the format SPI. Kotlin `PaymentRequest` builder DSL provides developer ergonomics — in Phase 1, the builder always produces the multipaz profile. Documentation leads with the builder.

**Rationale**: The wire format must match what the wallet understands. The multipaz wallet uses the multipaz profile; TS12 wallets don't exist yet. The builder hides the profile details so merchant code is stable across future profile changes.

**Alternatives considered**:
- Simple PaymentRequest as HTTP body: Rejected — invents a non-standard wire format
- Raw TS12 only (no builder): Rejected — violates constitution Principle I (Developer Simplicity)

## Decision 4: Transaction Data Profile Mismatch

**Decision**: Treat multipaz and TS12 as separate transaction data profiles. Store exact emitted bytes per session. Verify byte-exact. No silent coercion.

**Rationale**: Hash verification is byte-for-byte over the original base64url-encoded strings. The multipaz profile (`amount` as string, `transaction_data_hashes_alg` as array, flat structure) and TS12 profile (`amount` as number, `transaction_data_hashes_alg` as string, `payload` wrapper) produce different bytes. Silent conversion would break hash verification. The multipaz profile is transitional and will be deprecated on a defined timeline.

**Alternatives considered**:
- Support only TS12: Rejected — existing wallet produces multipaz profile; would require wallet migration before verifier can be tested
- Normalize at verification time: Rejected — any reserialization changes the hash input

## Decision 5: HTTP Semantics for Pending State

**Decision**: 202 Accepted with `Retry-After` header as default. Configuration flag for EUDI-compatible 400.

**Rationale**: Pending is not a client error. 202 communicates async processing correctly and enables proper polling backoff. The EUDI reference verifier's 400 is a pragmatic shortcut, not a standard. A config flag (`eudiCompat`) provides compatibility for teams integrating with existing EUDI clients.

**Alternatives considered**:
- 400 only: Rejected — semantically wrong; clients may interpret as "broken" instead of "retry"
- Long polling / SSE: Rejected — adds complexity without clear benefit for the POC

## Decision 6: Security Prerequisites

**Decision**: Define four hard prerequisites before implementation: session authentication, single-use sessions, terminal states, trust manager interface. Defer privacy/retention policy to hardening.

**Rationale**: These affect protocol shape and failure semantics and are expensive to retrofit. Merchant auth (128+ bit session IDs), single-use (first response wins), terminal states (verified/failed/declined/expired), and trust manager interface (strict-by-default) must be designed before code. Privacy defaults (no persistence beyond TTL, no claim logging) ship as conservative defaults with a documented hardening checklist.

**Alternatives considered**:
- Full trust model + retention policy before code: Rejected — front-loads design work that depends on implementation discovery
- No security gates: Rejected — a verifier that leaks results or accepts replays is worse than no verifier

## Decision 7: Code Reuse Strategy

**Decision**: Extract protocol logic from `multipaz-verifier-server` and repackage behind a clean API. Use `multipaz` core primitives directly.

**Rationale**: The existing verifier-server has all the protocol logic (session creation, JAR signing, JWE decryption, mdoc verification, hash checking) but it's embedded in monolithic handler functions. The new module calls the same core primitives (`OpenID4VP.generateRequest()`, `DeviceResponse.verify()`, `JsonWebEncryption.decrypt()`, `buildJwt()`) but wraps them in a clean plugin architecture. No code is copied — the new module imports from core.

**Key code to reuse**:
- `OpenID4VP.generateRequest()` → request building
- `DeviceResponse.verify()` → mdoc verification
- `JsonWebEncryption.decrypt()` → response decryption
- `buildJwt()` → JAR signing
- `TransactionData.parse()` → transaction data handling
- `DcqlQuery` → credential query construction
