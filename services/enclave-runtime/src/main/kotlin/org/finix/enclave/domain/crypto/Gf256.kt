package org.finix.enclave.domain.crypto

/**
 * Galois Field GF(2⁸) with AES reduction polynomial `0x11b`.
 * Duplicated inside the enclave for isolation from vault's classpath.
 */
object Gf256 {

    private const val GENERATOR = 0x03

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = mulRaw(x, GENERATOR)
        }
        for (i in 255 until 512) {
            exp[i] = exp[i - 255]
        }
        log[0] = 0
    }

    private fun mulRaw(a: Int, b: Int): Int {
        var aa = a and 0xFF
        var bb = b and 0xFF
        var p = 0
        while (bb != 0) {
            if ((bb and 1) != 0) p = p xor aa
            val hi = (aa and 0x80) != 0
            aa = (aa shl 1) and 0xFF
            if (hi) aa = aa xor 0x1B
            bb = bb shr 1
        }
        return p and 0xFF
    }

    fun add(a: Int, b: Int): Int = (a xor b) and 0xFF

    fun sub(a: Int, b: Int): Int = add(a, b)

    fun mul(a: Int, b: Int): Int {
        val aa = a and 0xFF
        val bb = b and 0xFF
        if (aa == 0 || bb == 0) return 0
        return exp[log[aa] + log[bb]]
    }

    fun div(a: Int, b: Int): Int {
        val aa = a and 0xFF
        val bb = b and 0xFF
        require(bb != 0) { "division by zero in GF(256)" }
        if (aa == 0) return 0
        return exp[log[aa] + 255 - log[bb]]
    }

    fun pow(base: Int, exponent: Int): Int {
        require(exponent >= 0) { "negative exponent" }
        var result = 1
        var b = base and 0xFF
        var e = exponent
        while (e > 0) {
            if ((e and 1) == 1) result = mul(result, b)
            b = mul(b, b)
            e = e shr 1
        }
        return result
    }
}
