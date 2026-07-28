package org.finix.kernel.crypto

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * RFC 8785 JSON Canonicalization Scheme (JCS).
 *
 * The ledger hash chain is verified by three different runtimes — the Kotlin ledger service,
 * a shell script, and the browser — so "hash the JSON" is only meaningful if all three
 * serialise identically. JCS fixes that: object keys sorted by UTF-16 code unit, no insignificant
 * whitespace, ECMAScript `Number::toString` number formatting, and minimal string escaping.
 *
 * Every `String.format` here pins [Locale.ROOT]: under a comma-decimal locale the default
 * would emit `1,5e+00` and two graders would compute different ledger hashes for the same data.
 *
 * Deliberately not supported: NaN/Infinity (not representable in JSON) and numbers outside the
 * IEEE-754 double range, both of which would make cross-runtime agreement impossible.
 */
object CanonicalJson {

    fun canonicalize(node: JsonNode): String = buildString { write(node, this) }

    fun canonicalBytes(node: JsonNode): ByteArray =
        canonicalize(node).toByteArray(StandardCharsets.UTF_8)

    private fun write(node: JsonNode, out: StringBuilder) {
        when {
            node.isObject -> writeObject(node as ObjectNode, out)
            node.isArray -> writeArray(node as ArrayNode, out)
            node.isTextual -> writeString(node.textValue(), out)
            node.isNumber -> out.append(formatNumber(node.decimalValue()))
            node.isBoolean -> out.append(if (node.booleanValue()) "true" else "false")
            node is NullNode -> out.append("null")
            else -> throw IllegalArgumentException("Cannot canonicalize node of type ${node.nodeType}")
        }
    }

    private fun writeObject(node: ObjectNode, out: StringBuilder) {
        // JCS orders members by the UTF-16 code units of the key, which is exactly
        // Kotlin's natural String ordering.
        val keys = node.fieldNames().asSequence().sorted().toList()
        out.append('{')
        keys.forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            writeString(key, out)
            out.append(':')
            write(node.get(key), out)
        }
        out.append('}')
    }

    private fun writeArray(node: ArrayNode, out: StringBuilder) {
        out.append('[')
        node.forEachIndexed { index, element ->
            if (index > 0) out.append(',')
            write(element, out)
        }
        out.append(']')
    }

    /**
     * RFC 8785 §3.2.2.2 string escaping: escape only what JSON requires, prefer the short
     * two-character forms, and lower-case hex for the remaining control characters.
     */
    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (ch < ' ') {
                        out.append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
            }
        }
        out.append('"')
    }

    /**
     * RFC 8785 §3.2.2.3 requires ES6 `Number::toString` output. Double.toString in Java differs
     * from ECMAScript (e.g. Java emits `1.0E21`, ES emits `1e+21`), so normalise explicitly.
     */
    internal fun formatNumber(value: BigDecimal): String {
        val d = value.toDouble()
        require(d.isFinite()) { "NaN and Infinity are not valid JSON numbers" }
        if (d == 0.0) return "0"

        // Every value -- integral or not -- goes through the shortest round-tripping
        // representation. Printing the exact binary value instead would emit
        // 123456789012345683968 where ECMAScript emits 123456789012345680000.
        return shortestRoundTrip(d)
    }

    private fun shortestRoundTrip(d: Double): String {
        // Find the shortest decimal representation that still round-trips, then render it
        // in ECMAScript notation.
        for (precision in 1..17) {
            val candidate = String.format(Locale.ROOT, "%.${precision}e", d)
            if (candidate.toDouble() == d) return toEcmaScriptForm(candidate)
        }
        return toEcmaScriptForm(String.format(Locale.ROOT, "%.17e", d))
    }

    private fun toEcmaScriptForm(scientific: String): String {
        val (mantissaPart, exponentPart) = scientific.split("e", "E")
        val exponent = exponentPart.toInt()
        val negative = mantissaPart.startsWith("-")
        val digits = mantissaPart.removePrefix("-").replace(".", "").trimEnd('0').ifEmpty { "0" }
        val sign = if (negative) "-" else ""

        return when {
            // ECMAScript prints plain decimal while the decimal exponent is in [-6, 21),
            // and switches to exponent form outside it: 1e-6 -> "0.000001", 1e-7 -> "1e-7".
            exponent in 0..20 -> {
                val intLen = exponent + 1
                sign + if (digits.length <= intLen) {
                    digits.padEnd(intLen, '0')
                } else {
                    digits.substring(0, intLen) + "." + digits.substring(intLen)
                }
            }
            exponent in -6..-1 -> sign + "0." + "0".repeat(-exponent - 1) + digits
            else -> {
                val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
                val expSign = if (exponent >= 0) "+" else "-"
                "$sign${mantissa}e$expSign${Math.abs(exponent)}"
            }
        }
    }
}
