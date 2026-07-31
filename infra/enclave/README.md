# Vault → enclave wiring (demo / compose)
#
# In vault-service application.yml:
#   finix.enclave.base-url: http://localhost:8090
#   # or http://enclave-runtime:8090 inside compose
#
# Trust material (public halves of classpath keys shipped with enclave-runtime):
#   infra/enclave/attestation-public.b64   — ML-DSA-65 verify of POST /attest
#   infra/enclave/mlkem-public.b64         — HybridSeal recipient
#   infra/enclave/x25519-public.b64        — HybridSeal recipient
#
# Wire format is identical to org.finix.vault.application.HybridSeal — see KDoc on
# org.finix.enclave.domain.crypto.HybridSeal.
#
# POST /reconstruct body:
#   sealedShares[].x          = SealedShard.shareIndex
#   sealedShares[].sealedB64  = HybridSeal packed ciphertext
#   sealedNetworkConfigB64    = HybridSeal.sealWithRawKey output (nonce||ct)
#   commitmentsB64            = Feldman commitments (accepted; verified in vault)
