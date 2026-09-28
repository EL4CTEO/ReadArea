package com.readarea.core.format

class CssRule(val tag: String?, val classes: List<String>, val id: String?, val props: Map<String, String>, val specificity: Int, val order: Int)

class Stylesheet {
    private val rules = ArrayList<CssRule>()
    private val byClass = HashMap<String, MutableList<CssRule>>()
    private val byTag = HashMap<String, MutableList<CssRule>>()
    private val byId = HashMap<String, MutableList<CssRule>>()
    private var counter = 0

    val isEmpty: Boolean get() = rules.isEmpty()

    fun add(css: String) = add(stripComments(css), 0)

    private fun add(cleaned: String, depth: Int) {
        var i = 0
        val n = cleaned.length
        while (i < n) {
            val open = cleaned.indexOf('{', i)
            if (open < 0) break
            val selectorText = cleaned.substring(i, open).trim()
            if (selectorText.startsWith("@")) {
                val close = matchingBrace(cleaned, open)
                // Real stylesheets nest media queries a level or two; a crafted one could nest them until the stack runs out.
                if (selectorText.startsWith("@media", ignoreCase = true) && close > open && depth < MAX_MEDIA_DEPTH) {
                    val inner = cleaned.substring(open + 1, close)
                    if (!selectorText.contains("print", ignoreCase = true) || selectorText.contains("screen", ignoreCase = true)) add(inner, depth + 1)
                }
                i = if (close < 0) n else close + 1
                continue
            }
            val close = cleaned.indexOf('}', open)
            if (close < 0) break
            val props = parseDeclarations(cleaned.substring(open + 1, close))
            if (props.isNotEmpty()) {
                selectorText.split(',').forEach { addSelector(it.trim(), props) }
            }
            i = close + 1
        }
    }

    private fun matchingBrace(s: String, open: Int): Int {
        var depth = 0
        for (k in open until s.length) {
            when (s[k]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return k
                }
            }
        }
        return -1
    }

    private fun addSelector(selector: String, props: Map<String, String>) {
        if (selector.isEmpty()) return
        val last = selector.split(SPLIT).lastOrNull { it.isNotBlank() }?.trim() ?: return
        if (last.contains(':') || last.contains('[') || last.contains('*') && last.length > 1) return
        var tag: String? = null
        val classes = ArrayList<String>()
        var id: String? = null
        val parts = PART.findAll(last)
        for (p in parts) {
            val v = p.value
            when {
                v.startsWith(".") -> classes.add(v.substring(1).lowercase())
                v.startsWith("#") -> id = v.substring(1)
                v == "*" -> {}
                else -> tag = v.lowercase()
            }
        }
        if (tag == null && classes.isEmpty() && id == null) return
        val spec = (if (id != null) 100 else 0) + classes.size * 10 + (if (tag != null) 1 else 0)
        val rule = CssRule(tag, classes, id, props, spec, counter++)
        rules.add(rule)
        when {
            id != null -> byId.getOrPut(id) { ArrayList() }.add(rule)
            classes.isNotEmpty() -> byClass.getOrPut(classes[0]) { ArrayList() }.add(rule)
            else -> byTag.getOrPut(tag!!) { ArrayList() }.add(rule)
        }
    }

    fun compute(tag: String, classAttr: String?, idAttr: String?, inline: String?): Map<String, String> {
        if (rules.isEmpty() && inline.isNullOrBlank()) return emptyMap()
        val classes = classAttr?.lowercase()?.split(WS)?.filter { it.isNotEmpty() } ?: emptyList()
        val matched = ArrayList<CssRule>()
        byTag[tag]?.let { matched.addAll(it) }
        for (c in classes) byClass[c]?.forEach { r -> if (r.matches(tag, classes, idAttr)) matched.add(r) }
        if (idAttr != null) byId[idAttr]?.forEach { r -> if (r.matches(tag, classes, idAttr)) matched.add(r) }
        if (matched.isEmpty() && inline.isNullOrBlank()) return emptyMap()
        matched.sortWith(compareBy({ it.specificity }, { it.order }))
        val out = HashMap<String, String>()
        for (r in matched) out.putAll(r.props)
        if (!inline.isNullOrBlank()) out.putAll(parseDeclarations(inline))
        return out
    }

    private fun CssRule.matches(t: String, cls: List<String>, idAttr: String?): Boolean {
        if (tag != null && tag != t) return false
        if (id != null && id != idAttr) return false
        return classes.all { it in cls }
    }

    companion object {
        private const val MAX_MEDIA_DEPTH = 8
        private val SPLIT = Regex("[\\s>+~]+")
        private val PART = Regex("[.#]?[A-Za-z0-9_\\-]+|\\*")
        private val WS = Regex("\\s+")

        /**
         * Replaces each comment with a space. A lazy regex would do this in quadratic time on a stylesheet full of
         * unclosed comments, retrying each one against the rest of the text; an unclosed comment runs to the end,
         * as it does in browsers.
         */
        fun stripComments(css: String): String {
            var open = css.indexOf("/*")
            if (open < 0) return css
            val out = StringBuilder(css.length)
            var copied = 0
            while (open >= 0) {
                out.append(css, copied, open).append(' ')
                val close = css.indexOf("*/", open + 2)
                if (close < 0) return out.toString()
                copied = close + 2
                open = css.indexOf("/*", copied)
            }
            return out.append(css, copied, css.length).toString()
        }

        fun parseDeclarations(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            for (decl in text.split(';')) {
                val colon = decl.indexOf(':')
                if (colon <= 0) continue
                val key = decl.substring(0, colon).trim().lowercase()
                val value = decl.substring(colon + 1).replace("!important", "").trim().lowercase()
                if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
            }
            return out
        }

        fun fontScale(value: String?): Float? {
            if (value == null) return null
            return when {
                value.endsWith("em") && !value.endsWith("rem") -> value.removeSuffix("em").trim().toFloatOrNull()
                value.endsWith("rem") -> value.removeSuffix("rem").trim().toFloatOrNull()
                value.endsWith("%") -> value.removeSuffix("%").trim().toFloatOrNull()?.div(100f)
                value.endsWith("px") -> value.removeSuffix("px").trim().toFloatOrNull()?.div(16f)
                value.endsWith("pt") -> value.removeSuffix("pt").trim().toFloatOrNull()?.div(12f)
                value == "xx-small" -> 0.6f
                value == "x-small" -> 0.75f
                value == "small" || value == "smaller" -> 0.89f
                value == "medium" -> 1f
                value == "large" || value == "larger" -> 1.2f
                value == "x-large" -> 1.5f
                value == "xx-large" -> 2f
                else -> null
            }?.coerceIn(0.5f, 2.5f)
        }
    }
}
