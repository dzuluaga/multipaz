# Recoverable hardware-backed Hedera account — design

> Status: approved design (2026-06-15). Scope: testapp x402 Hedera demo
> (`samples/testapp`, branch `hedera-credential-demo`). Next: implementation plan.

## Goal

Make the wallet's customer Hedera account **recoverable** without weakening the
hardware-key property. The account's on-chain key becomes a **1-of-2 threshold**
of [phone TEE key, recovery key]. The phone alone signs everyday payments; a
saved recovery key can rotate the account to a new device if the phone is lost.

The recovery **private key is shown to the user exactly once** (text + QR), the
user saves it externally (password manager), and the app then **discards it and
never persists it**. This is the canonical "seed shown once" pattern, kept
deliberately minimal.

## Decisions (from brainstorming)

- **Recovery model:** recovery *phrase / self-custody*. The backup leaf is a key
  the user saves themselves; no second device, no cloud, no guardians.
- **Account lifecycle:** *born recoverable*. New accounts are created with the
  threshold key from the start (no rotation, no vulnerable window). Upgrading a
  pre-existing TEE-only account via rotation is **not** in scope.
- **Scope:** *setup / display only*. The account is made recoverable and the
  recovery key is shown once. Actually *performing* recovery (using the saved
  key on a new device) is out of scope for v1 — done externally with the saved
  key (e.g. the `AccountUpdate` rotate script).
- **Recovery key format:** a plain Ed25519 private-key string (DER, the same form
  the Hedera portal shows). Works with any password manager and the rotate
  script. A friendlier BIP-39 mnemonic is a possible later enhancement, not v1.

## Non-goals (v1)

- In-app recovery flow (import the saved key on a new device → rotate).
- Rotating/“upgrading” an existing TEE-only account to the threshold.
- Multi-key / 2-of-3 / social / cloud recovery.
- Any persistent storage of the recovery private key.

## Architecture (three touch points)

1. **`provisionRecoverableAccount(...)`** — `HederaSettlement.kt`.
   Replaces the single-key creation in `provisionAccount`. Generates a one-shot
   recovery keypair in memory, creates the account keyed by a 1-of-2 threshold,
   and returns both the `accountId` (to persist) and the recovery **private** key
   string (to display once and then drop).

   ```kotlin
   val recovery = PrivateKey.generateED25519()                 // in-memory only
   val thresholdKey = KeyList.of(teePublicKey, recovery.publicKey).setThreshold(1) // 1-of-2
   val accountId = AccountCreateTransaction()
       .setKey(thresholdKey)
       .setInitialBalance(initialBalance)
       .execute(client).getReceipt(client).accountId!!
   return RecoverableProvisionResult(accountId, recovery.toString())
   ```
   The recovery **public** key goes on-chain; the **private** key is returned to
   the caller and never written to disk.

2. **`RecoveryKeyScreen`** — a new one-time Compose screen (Android), shown right
   after the account is first created (on first Pay). See "Recovery screen".
   On confirm, the recovery key string is cleared from memory; the screen cannot
   be reopened to reveal it.

3. **`recoverable` flag on `HederaAccountCredential`** — a serialized boolean so
   the wallet records that the account was set up this way. No key material is
   added to the credential beyond what already exists (the credential keeps only
   the TEE key handle + accountId, optionally the recovery *public* key for
   reference).

No new subsystem, no key store, no changes to the SecureArea/credential model
beyond the flag.

## Recovery screen

Shown once, immediately after the on-chain account is created.

```
Your wallet key lives in this phone's secure hardware (TEE).
It can't be exported — even by you.

This is your RECOVERY key. It's shown ONCE. If you lose this
phone, it's the only way back into your account.

   [ QR code ]      302e0201…  [Copy]

Save it now → paste into a password manager, or scan the QR.

☐ I've saved my recovery key
            [ Continue ]   (disabled until the box is checked)
```

Behavior:
- **Continue** is disabled until the acknowledgment checkbox is ticked.
- On **Continue**: the recovery private-key string is wiped from memory; the
  screen is dismissed and cannot be reopened to reveal the key.
- QR reuses the existing multipaz-compose QR rendering. The encoded payload is
  the recovery key string.

## Safety invariant

The single rule the whole design rests on: **the app never persists the recovery
private key.**

| Held by | TEE key | Recovery public | Recovery private | accountId |
|---|---|---|---|---|
| Secure element | yes (non-extractable) | — | — | — |
| App storage (DocumentStore) | handle only | cached (optional) | **never** | yes |
| On-chain (account key field) | yes | yes | — | — |
| The user (password manager) | — | — | **only here** | — |

## Payments

Unchanged. Because the account key is `Threshold(1, [TEE, recovery])`, the TEE's
single signature (via the existing `signFrozenWithCredential`) satisfies the
1-of-2 threshold, so the current Pay flow works as-is.

**Open risk to verify:** that the blocky402 facilitator settles a
**threshold-keyed payer** (the dossier's Lab 4 open question). The phone's single
signature satisfies 1-of-N, so it should settle — confirm with a live testnet
check before calling the feature done.

## Testing

- **Host unit test** (`SoftwareSecureArea`, no network): `provisionRecoverableAccount`
  builds a 1-of-2 `KeyList` from `[teePublicKey, recoveryPublicKey]`, returns the
  recovery private key, and persists nothing of it. (Account creation itself is
  on-chain; the offline test asserts the key structure passed to
  `AccountCreateTransaction`, factored so it's testable without the network.)
- **Live check (testnet):** create a recoverable account; assert via the mirror
  node that the account's key is a 1-of-2 threshold; assert a payment still
  settles with the TEE signature alone (resolves the threshold-payer risk).
- **Recovery screen:** the recovery key is shown once and wiped on Continue;
  re-entering the screen never reveals it. (UI / manual.)

## Expectation-setting (the user-facing promise)

The screen copy teaches the model: hardware key that can't be exported, one-time
recovery key that is the *only* backup, save it now. The implicit contract: if
the user neither saves the recovery key nor keeps the phone, the account is
unrecoverable — which is the honest, correct property of self-custody.
