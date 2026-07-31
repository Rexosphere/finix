package org.finix.vault.domain.crypto

/**
 * Arithmetic in GF(2⁸) with the AES reduction polynomial `0x11b` (`x⁸ + x⁴ + x³ + x + 1`).
 *
 * Log/antilog tables are built with generator **0x03** (Rijndael's standard generator).
 */
object Gf256 {

    const val REDUCTION_POLYNOMIAL: Int = 0x11b
    private const val GENERATOR: Int = 0x03

    private val LOG = IntArray(256)
    private val EXP = IntArray(512)

    init {
        var value = 1
        for (i in 0 until 255) {
            EXP[i] = value
            LOG[value] = i
            // Multiply by generator 0x03 in the AES field: x * 3 = (x << 1) ⊕ x, reduced.
            value = mulRaw(value, GENERATOR)
        }
        for (i in 255 until 512) {
            EXP[i] = EXP[i - 255]
        }
        LOG[0] = 0
    }

    /** Russian-peasant multiply used only while building tables (before EXP/LOG are ready). */
    private fun mulRaw(a: Int, b: Int): Int {
        var aa = a and 0xff
        var bb = b and 0xff
        var p = 0
        while (bb != 0) {
            if ((bb and 1) != 0) p = p xor aa
            val hi = (aa and 0x80) != 0
            aa = (aa shl 1) and 0xff
            if (hi) aa = aa xor 0x1b
            bb = bb shr 1
        }
        return p and 0xff
    }

    fun add(a: Int, b: Int): Int = (a xor b) and 0xff

    fun sub(a: Int, b: Int): Int = add(a, b)

    fun mul(a: Int, b: Int): Int {
        val aa = a and 0xff
        val bb = b and 0xff
        if (aa == 0 || bb == 0) return 0
        return EXP[LOG[aa] + LOG[bb]]
    }

    fun div(a: Int, b: Int): Int {
        val aa = a and 0xff
        val bb = b and 0xff
        require(bb != 0) { "division by zero in GF(256)" }
        if (aa == 0) return 0
        return EXP[LOG[aa] + 255 - LOG[bb]]
    }

    fun inv(a: Int): Int {
        val aa = a and 0xff
        require(aa != 0) { "inverse of zero in GF(256)" }
        return EXP[255 - LOG[aa]]
    }

    fun pow(base: Int, exponent: Int): Int {
        require(exponent >= 0) { "negative exponent" }
        var result = 1
        var b = base and 0xff
        var e = exponent
        while (e > 0) {
            if ((e and 1) == 1) result = mul(result, b)
            b = mul(b, b)
            e = e shr 1
        }
        return result
    }
}
