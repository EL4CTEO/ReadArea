package com.readarea.core.format

class XmlNode(val name: String, val attrs: Attributes, val parent: XmlNode?) {
    val children = ArrayList<XmlNode>()
    private val textParts = ArrayList<Any>()

    val content: List<Any> get() = textParts

    val text: String
        get() = buildString { collect(this) }

    private fun collect(sb: StringBuilder) {
        for (p in textParts) {
            if (p is String) sb.append(p) else (p as XmlNode).collect(sb)
        }
    }

    fun ownText(): String = textParts.filterIsInstance<String>().joinToString("")

    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<XmlNode> = children.filter { it.name == name }

    fun find(name: String): XmlNode? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    fun findAll(name: String, out: MutableList<XmlNode> = ArrayList()): List<XmlNode> {
        for (c in children) {
            if (c.name == name) out.add(c)
            c.findAll(name, out)
        }
        return out
    }

    operator fun get(attr: String): String? = attrs[attr]

    internal fun addText(t: String) {
        textParts.add(t)
    }

    internal fun addChild(n: XmlNode) {
        children.add(n)
        textParts.add(n)
    }

    companion object {
        /**
         * Nesting deeper than this is flattened: no real document comes close, and the cap keeps a crafted
         * file from exhausting the stack in the recursive walks or making each end tag search a long chain.
         */
        const val MAX_DEPTH = 256

        fun parse(xml: String): XmlNode {
            val root = XmlNode("#root", Attributes.EMPTY, null)
            var cur = root
            var depth = 0
            var overflow = 0
            HtmlTokenizer(xml).parse(object : HtmlHandler {
                override fun startTag(name: String, attrs: Attributes, selfClosing: Boolean) {
                    val node = XmlNode(name, attrs, cur)
                    cur.addChild(node)
                    if (selfClosing) return
                    if (depth < MAX_DEPTH) {
                        cur = node
                        depth++
                    } else {
                        overflow++
                    }
                }

                override fun endTag(name: String) {
                    if (overflow > 0) {
                        overflow--
                        return
                    }
                    var n: XmlNode? = cur
                    var up = 0
                    while (n != null && n.name != name) {
                        n = n.parent
                        up++
                    }
                    if (n?.parent != null) {
                        cur = n.parent!!
                        depth -= up + 1
                    }
                }

                override fun text(text: String) {
                    cur.addText(text)
                }

                override fun rawText(tag: String, content: String) {
                    cur.addText(content)
                }
            })
            return root
        }
    }
}
