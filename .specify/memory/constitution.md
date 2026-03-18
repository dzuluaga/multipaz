<!--
Sync Impact Report
- Version change: 0.0.0 → 1.0.0
- Modified principles: N/A (initial ratification)
- Added sections: Core Principles (6), Technical Constraints, Development Workflow
- Removed sections: N/A
- Templates requiring updates:
  - .specify/templates/plan-template.md ✅ reviewed (Constitution Check section will reference these principles)
  - .specify/templates/spec-template.md ✅ reviewed (no changes needed — spec template is generic)
  - .specify/templates/tasks-template.md ✅ reviewed (no changes needed — task structure is generic)
- Follow-up TODOs: None
-->

# multipaz-dpc-verifier Constitution

## Core Principles

### I. Developer Simplicity

A developer MUST be able to go from zero to a working DPC verifier in under 20 lines of Kotlin. The library MUST hide all OpenID4VP ceremony details (session creation, nonce generation, key exchange, JWE decryption, DCQL construction) behind a declarative Ktor routing DSL. If a developer needs to read the OpenID4VP spec to use this library, we have failed.

Rationale: The existing `multipaz-verifier-server` requires ~2500 LOC across multiple files to achieve what should be a configuration-level task. The primary value proposition of this library is radical simplicity.

### II. OpenID4VP Protocol Compliance

All protocol handling MUST follow OpenID4VP 1.0 (finalized July 2025) and HAIP 1.0. The library MUST correctly implement: authorization request generation with DCQL, signed JAR serving via `request_uri`, `direct_post.jwt` response mode, JWE response decryption, and session transcript construction. Protocol behavior MUST NOT be invented or deviated from — if the spec says it, we do it; if the spec doesn't say it, we don't.

Rationale: Interoperability with real wallets (including multipaz's own wallet) depends on strict protocol adherence. A non-compliant verifier is useless regardless of how clean its API is.

### III. Verification Correctness

The DPC verification contract MUST be implemented completely and atomically. All verification steps MUST pass or the entire verification MUST fail — no partial results. The required steps are:
1. Issuer trust verification (COSE_Sign1 signature, certificate chain, expiry)
2. Device authentication verification (device key binding, session transcript)
3. Transaction data hash binding verification (hash count, algorithm, value matching)
4. Payment-specific validation (payment_instrument_id presence, doctype confirmation)

Rationale: A verifier that accepts invalid proofs is worse than no verifier at all. The verification contract from the Phase 1B DPC spec (contracts/payment-transaction-data.md, steps 1-12) is the authoritative reference.

### IV. Ktor-Native Design

The library MUST be built as a first-class Ktor plugin, not a framework-agnostic library with adapters. It MUST embrace Ktor idioms: `install()` for configuration, routing DSL for endpoint declaration, content negotiation for serialization, and coroutine-based request handling. The API MUST feel natural to a Ktor developer — no wrappers, no framework abstractions, no impedance mismatch.

Rationale: Attempting framework-agnostic design adds complexity without delivering value for the POC. Ktor is the target runtime, and the existing multipaz server infrastructure already uses Ktor with Netty.

### V. Reusable Foundation

While the first use case is DPC (`org.multipaz.payment.sca.1`), the protocol engine (session management, OpenID4VP request/response, mdoc verification) MUST be credential-type-agnostic. DPC-specific logic (payment fields, transaction data semantics) MUST live in a thin layer that does not pollute the core verification pipeline. Adding support for a new mdoc credential type (e.g., mDL, EUDI PID) MUST NOT require modifying core protocol code.

Rationale: The library's long-term value is as a reusable OpenID4VP verifier, not a DPC-only tool. Coupling protocol logic to DPC semantics would require a rewrite when the next credential type arrives.

### VI. Spec-Driven with Visual Documentation

Every feature MUST have a human-readable specification that includes sequence diagrams showing the protocol flow between all participants (merchant, verifier, wallet). The spec MUST be written and reviewed before implementation begins. Code follows spec, not the other way around. Diagrams MUST use ASCII art or Mermaid so they render in plain Markdown without external tools.

Rationale: The OpenID4VP protocol involves multiple parties and asynchronous message flows. Without visual documentation, contributors cannot reason about correctness and reviewers cannot evaluate changes.

## Technical Constraints

- **Language**: Kotlin/JVM (Java 17 target)
- **Framework**: Ktor 3.x with Netty engine
- **Dependencies**: MUST depend on `multipaz` (core) and `multipaz-doctypes` for crypto, mdoc, DCQL, and trust management. MUST NOT duplicate logic that already exists in multipaz core.
- **Module**: New Gradle module `multipaz-dpc-verifier` within the multipaz monorepo
- **Storage**: In-memory session storage for the POC. The `SessionStorage` interface MUST be pluggable for future JDBC/Redis implementations.
- **Serialization**: kotlinx.serialization for JSON. CBOR via multipaz core.
- **No new crypto**: All cryptographic operations (ECDSA, HPKE, JWE, COSE) MUST use multipaz core primitives. No direct BouncyCastle or JCA usage in this module.

## Development Workflow

- **Spec first**: Write the specification (including sequence diagrams) before writing code.
- **Spec commands**: Use the speckit workflow (`/speckit.specify` → `/speckit.plan` → `/speckit.tasks` → `/speckit.implement`) to drive development.
- **Single commit discipline**: Each task produces a clean, reviewable commit.
- **Test with existing wallet**: Validate against the multipaz test app wallet, which already supports DPC with transactional data (branch `002-dpc-transaction-auth`).
- **Incremental delivery**: MVP is a working DPC verification with a single `dpcVerification()` route. Polish, multi-protocol support, and additional credential types come later.

## Governance

This constitution governs the `multipaz-dpc-verifier` module within the multipaz monorepo. Amendments require updating this document with a version bump, re-running the consistency propagation checklist, and documenting the change rationale. All code changes MUST be reviewed against these principles before merge.

**Version**: 1.0.0 | **Ratified**: 2026-03-17 | **Last Amended**: 2026-03-17
