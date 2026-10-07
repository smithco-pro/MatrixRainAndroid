package com.aftersix.matrixrain.core

/** Minimal JSON for run.json and results; avoids a dependency that both the JVM tests and Android would need. */
object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    fun parse(text: String): Any? = Parser(text).run {
        val value = value()
        skipWhitespace()
        if (index != text.length) fail("trailing characters")
        value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw IllegalArgumentException("JSON object expected")

    private fun append(b: StringBuilder, value: Any?) {
        when (value) {
            null -> b.append("null")
            is String -> quote(b, value)
            is Boolean, is Int, is Long, is Short, is Byte -> b.append(value)
            is Double -> if (value.isFinite()) b.append(value) else b.append("null")
            is Float -> append(b, value.toDouble())
            is Number -> b.append(value.toString())
            is Enum<*> -> quote(b, value.name)
            is Map<*, *> -> {
                b.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) b.append(',')
                    first = false
                    quote(b, k.toString())
                    b.append(':')
                    append(b, v)
                }
                b.append('}')
            }
            is Iterable<*> -> {
                b.append('[')
                var first = true
                for (v in value) {
                    if (!first) b.append(',')
                    first = false
                    append(b, v)
                }
                b.append(']')
            }
            is Array<*> -> append(b, value.asList())
            else -> quote(b, value.toString())
        }
    }

    private fun quote(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        b.append('"')
    }

    private class Parser(val s: String) {
        var index = 0

        fun fail(message: String): Nothing = throw IllegalArgumentException("Invalid JSON at $index: $message")

        fun skipWhitespace() {
            while (index < s.length && s[index].isWhitespace()) index++
        }

        fun value(): Any? {
            skipWhitespace()
            if (index >= s.length) fail("unexpected end")
            return when (val c = s[index]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, index)) fail("expected $word")
            index += word.length
            return result
        }

        fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            index++
            skipWhitespace()
            if (s.getOrNull(index) == '}') { index++; return map }
            while (true) {
                skipWhitespace()
                if (s.getOrNull(index) != '"') fail("key expected")
                val key = str()
                skipWhitespace()
                if (s.getOrNull(index++) != ':') fail("':' expected")
                map[key] = value()
                skipWhitespace()
                when (s.getOrNull(index++)) {
                    ',' -> continue
                    '}' -> return map
                    else -> fail("',' or '}' expected")
                }
            }
        }

        fun arr(): List<Any?> {
            val list = ArrayList<Any?>()
            index++
            skipWhitespace()
            if (s.getOrNull(index) == ']') { index++; return list }
            while (true) {
                list += value()
                skipWhitespace()
                when (s.getOrNull(index++)) {
                    ',' -> continue
                    ']' -> return list
                    else -> fail("',' or ']' expected")
                }
            }
        }

        fun str(): String {
            val b = StringBuilder()
            index++
            while (true) {
                if (index >= s.length) fail("unterminated string")
                when (val c = s[index++]) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s.getOrNull(index++)) {
                        '"', '\\', '/' -> b.append(e)
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000c')
                        'u' -> {
                            if (index + 4 > s.length) fail("bad unicode escape")
                            b.append(s.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> fail("bad escape")
                    }
                    else -> b.append(c)
                }
            }
        }

        fun num(): Number {
            val start = index
            if (s[index] == '-') index++
            while (index < s.length && (s[index].isDigit() || s[index] in ".eE+-")) index++
            val text = s.substring(start, index)
            return if (text.any { it in ".eE" }) text.toDouble() else text.toLongOrNull() ?: fail("bad number")
        }
    }
}
