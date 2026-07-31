package org.finix.enclave.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "finix.enclave")
data class EnclaveProperties(
    val moduleId: String = "finix-enclave-1",
    val attestationSigningPrivate: String = "classpath:attestation/signing-private.b64",
    val attestationSigningPublic: String = "classpath:attestation/signing-public.b64",
    val mlkemPrivate: String = "classpath:attestation/mlkem-private.b64",
    val mlkemPublic: String = "classpath:attestation/mlkem-public.b64",
    val x25519Private: String = "classpath:attestation/x25519-private.b64",
    val x25519Public: String = "classpath:attestation/x25519-public.b64",
)
