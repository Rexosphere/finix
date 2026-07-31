package org.finix.enclave.domain.crypto

/**
 * Explicit zeroing for key material. Package-visible so tests can assert wipe was invoked
 * without reflecting into private methods.
 */
internal object SecureBytes {

    /** Test-visible counter of successful wipe calls (non-null buffers only). */
    @Volatile
    var wipeInvocations: Int = 0
        private set

    fun resetWipeCounter() {
        wipeInvocations = 0
    }

    /** Overwrites every element with `0`. Safe to call on null (no-op). */
    fun wipe(buffer: ByteArray?) {
        if (buffer == null) return
        buffer.fill(0)
        wipeInvocations++
    }

    fun wipeAll(vararg buffers: ByteArray?) {
        buffers.forEach { wipe(it) }
    }
}
