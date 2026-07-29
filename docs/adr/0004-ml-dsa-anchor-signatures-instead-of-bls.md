# 4. ML-DSA-65 anchor signatures instead of BLS aggregation

- Status: Accepted
- Date: 2026-07-29

## Context

Blueprint §2.2.5 states that ledger anchors are signed "using BLS signatures", with the intent that
multiple notaries' signatures over the same Merkle root aggregate into one constant-size signature.

BouncyCastle 1.82 — the cryptographic provider used throughout FINIX — ships **no BLS12-381 JCA
signature provider**. Implementing pairing-based BLS from scratch is not something that should be
hand-rolled for a system that claims to protect money.

Meanwhile the blueprint's *other* cryptographic commitment (§2.2.3, §6.1) is post-quantum signatures
via CRYSTALS-Dilithium. BLS is **not** post-quantum secure — it is broken by Shor's algorithm — so
signing a 2065-threat-model audit trail with BLS would contradict §6.1 anyway.

## Decision

Anchor signing uses **ML-DSA-65** (FIPS 204, the standardised CRYSTALS-Dilithium), verified working
against BouncyCastle 1.82 on JDK 21. Multi-notary anchoring is expressed as a **list of independent
ML-DSA signatures** over the same Merkle root, with a threshold policy, rather than as one
aggregated signature.

BLS aggregation is recorded in `docs/FIDELITY-MATRIX.md` as **not implemented**, with this rationale.

## Consequences

**Positive.** The audit trail is genuinely post-quantum, which is the stronger of the two blueprint
claims and the one that matches the 2065 scenario. The primitive is standardised and provider-backed
rather than hand-written.

**Negative.** Anchor records grow linearly with the notary count (ML-DSA-65 signatures are 3309
bytes each) instead of staying constant-size. At one anchor per 60 seconds and a handful of
notaries this is immaterial; at internet scale it would not be.
