package com.cheval.core

/**
 * Mini JSON (sans dépendance) pour les sauvegardes.
 * Valeurs : null, Boolean, Double, String, List<Any?>, Map<String, Any?>.
 */
object Json {
    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Float -> writeNum(sb, v.toDouble())
            is Double -> writeNum(sb, v)
            is String -> writeStr(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeStr(sb, k.toString()); sb.append(':'); write(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, e)
                }
                sb.append(']')
            }
            is IntArray -> write(sb, v.toList())
            is FloatArray -> write(sb, v.toList())
            is DoubleArray -> write(sb, v.toList())
            else -> writeStr(sb, v.toString())
        }
    }

    private fun writeNum(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) sb.append('0')
        else if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) sb.append(d.toLong())
        else sb.append(d)
    }

    private fun writeStr(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
        }
        sb.append('"')
    }

    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON tronqué" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c.isDigit()) num() else error("JSON invalide en $i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i] == ':'); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++; return l
            }
        }
        fun str(): String {
            require(s[i] == '"'); i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}

/** Accès typés tolérants pour relire une sauvegarde. */
@Suppress("UNCHECKED_CAST")
class JObj(val m: Map<String, Any?>) {
    fun int(k: String, d: Int = 0): Int = (m[k] as? Double)?.toInt() ?: d
    fun long(k: String, d: Long = 0): Long = (m[k] as? Double)?.toLong() ?: (m[k] as? String)?.toLongOrNull() ?: d
    fun float(k: String, d: Float = 0f): Float = (m[k] as? Double)?.toFloat() ?: d
    fun double(k: String, d: Double = 0.0): Double = (m[k] as? Double) ?: d
    fun bool(k: String, d: Boolean = false): Boolean = (m[k] as? Boolean) ?: d
    fun str(k: String, d: String = ""): String = (m[k] as? String) ?: d
    fun strOrNull(k: String): String? = m[k] as? String
    fun obj(k: String): JObj? = (m[k] as? Map<String, Any?>)?.let { JObj(it) }
    fun list(k: String): List<Any?> = (m[k] as? List<Any?>) ?: emptyList()
    fun objs(k: String): List<JObj> = list(k).mapNotNull { (it as? Map<String, Any?>)?.let { o -> JObj(o) } }
    fun ints(k: String): IntArray = list(k).map { (it as Double).toInt() }.toIntArray()
    fun floats(k: String): FloatArray = list(k).map { (it as Double).toFloat() }.toFloatArray()
    fun has(k: String) = m.containsKey(k) && m[k] != null
    inline fun <reified E : Enum<E>> enum(k: String, d: E): E =
        (m[k] as? String)?.let { s -> enumValues<E>().firstOrNull { it.name == s } } ?: d
}
