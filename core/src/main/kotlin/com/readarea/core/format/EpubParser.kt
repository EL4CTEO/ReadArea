package com.readarea.core.format

class EpubParser(private val zip: ZipAccess) {

    private class Item(val id: String, val href: String, val mediaType: String, val properties: String)

    fun parse(metadataOnly: Boolean = false): ParsedBook {
        val container = zip.read("META-INF/container.xml")?.let { XmlNode.parse(TextDecoder.decode(it)) }
        val opfPath = container?.find("rootfile")?.get("full-path")
            ?: zip.entries.firstOrNull { it.endsWith(".opf", true) }
            ?: throw BookParseException(ParseError.INVALID, "Invalid EPUB: package document not found")
        val opfBytes = zip.read(opfPath) ?: throw BookParseException(ParseError.INVALID, "Invalid EPUB: missing $opfPath")
        val opf = XmlNode.parse(TextDecoder.decode(opfBytes))

        val manifest = LinkedHashMap<String, Item>()
        opf.find("manifest")?.children("item")?.forEach { n ->
            val id = n["id"] ?: return@forEach
            val href = n["href"] ?: return@forEach
            manifest[id] = Item(id, PathUtil.resolve(opfPath, href), n["media-type"] ?: "", n["properties"] ?: "")
        }

        val md = opf.find("metadata")
        val title = md?.children("title")?.firstOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
        val creators = md?.children("creator").orEmpty()
        val authors = creators.filter { c ->
            val role = c["role"]
            role == null || role == "aut"
        }.ifEmpty { creators }.map { it.text.trim() }.filter { it.isNotEmpty() }.distinct()
        val language = md?.child("language")?.text?.trim()
        val description = md?.child("description")?.text?.let { stripTags(it) }?.takeIf { it.isNotBlank() }
        val publisher = md?.child("publisher")?.text?.trim()
        val metas = md?.children("meta").orEmpty()
        var series = metas.firstOrNull { it["name"] == "calibre:series" }?.get("content")
        var seriesIndex = metas.firstOrNull { it["name"] == "calibre:series_index" }?.get("content")?.toFloatOrNull()
        if (series == null) {
            val coll = metas.firstOrNull { it["property"] == "belongs-to-collection" }
            if (coll != null) {
                series = coll.text.trim()
                val cid = coll["id"]
                seriesIndex = metas.firstOrNull { it["property"] == "group-position" && it["refines"] == "#$cid" }?.text?.trim()?.toFloatOrNull()
            }
        }

        val coverRef = findCover(metas, manifest, opf, opfPath)
        val writingMode = metas.firstOrNull { it["name"] == "primary-writing-mode" }?.get("content")?.lowercase()
        val meta = BookMeta(
            title = title ?: "",
            author = authors.joinToString(", "),
            language = language,
            description = description,
            series = series?.takeIf { it.isNotBlank() },
            seriesIndex = seriesIndex,
            publisher = publisher,
            coverRef = coverRef,
            rtl = opf.find("spine")?.get("page-progression-direction")?.equals("rtl", true) ?: TextDirection.isRtlLanguage(language),
            vertical = writingMode?.startsWith("vertical") == true,
        )
        if (metadataOnly) return ParsedBook(meta, emptyList(), emptyList(), zip)
        checkDrm()

        val spine = opf.find("spine")
        val spineItems = spine?.children("itemref")?.mapNotNull { manifest[it["idref"]] }
            ?.filter { it.mediaType.contains("html") || it.href.endsWith("html", true) || it.href.endsWith(".htm", true) || it.mediaType.contains("svg") }
            .orEmpty()
        if (spineItems.isEmpty()) throw BookParseException(ParseError.EMPTY, "This EPUB has no readable content")

        val tocRaw = parseNav(manifest) ?: parseNcx(spine?.get("toc"), manifest) ?: emptyList()
        val cssCache = HashMap<String, String?>()
        val loadCss: (String) -> String? = { path -> cssCache.getOrPut(path) { zip.read(path)?.let { TextDecoder.decode(it) } } }

        val chapterIndex = HashMap<String, Int>()
        val chapters = ArrayList<Chapter>()
        var verticalVotes = 0
        var horizontalVotes = 0
        spineItems.forEachIndexed { i, item ->
            chapterIndex[item.href] = i
            val html = zip.read(item.href)?.let { TextDecoder.decode(it) } ?: ""
            val converter = HtmlConverter(item.href, Stylesheet(), loadCss)
            val blocks = converter.convert(html)
            if (converter.vertical) verticalVotes++ else if (converter.horizontalDeclared) horizontalVotes++
            val tocTitle = tocRaw.firstOrNull { it.first.substringBefore('#') == item.href }?.second
            val chapterTitle = tocTitle ?: converter.firstHeading ?: converter.docTitle?.takeIf { it != meta.title } ?: ""
            chapters.add(Chapter(chapterTitle, item.href, blocks))
        }
        val toc = tocRaw.mapNotNull { (href, t, depth) ->
            val file = href.substringBefore('#')
            val idx = chapterIndex[file] ?: return@mapNotNull null
            TocItem(t, idx, href.substringAfter('#', "").ifEmpty { null }, depth)
        }
        var finalMeta = if (meta.title.isEmpty()) meta.copy(title = chapters.firstOrNull { it.title.isNotEmpty() }?.title ?: "") else meta
        if (writingMode == null && verticalVotes > horizontalVotes) finalMeta = finalMeta.copy(vertical = true)
        if (finalMeta.coverRef == null) {
            chapters.take(3).firstNotNullOfOrNull { c -> c.blocks.firstOrNull { it.kind == BlockKind.IMAGE }?.image }?.let { finalMeta = finalMeta.copy(coverRef = it) }
        }
        return ParsedBook(finalMeta, chapters, toc, zip)
    }

    /**
     * Store books (Adobe ADEPT, Apple FairPlay, Readium LCP, Kobo) encrypt their chapters and list them in
     * META-INF/encryption.xml. Only font obfuscation, which leaves the text readable, is allowed.
     */
    private fun checkDrm() {
        if (zip.read("META-INF/license.lcpl") != null || zip.read("META-INF/rights.xml") != null) throw drm()
        val xml = zip.read("META-INF/encryption.xml") ?: return
        val root = XmlNode.parse(TextDecoder.decode(xml))
        val encrypted = root.findAll("encrypteddata").any { data ->
            val algorithm = data.find("encryptionmethod")?.get("algorithm").orEmpty()
            val uri = data.find("cipherreference")?.get("uri").orEmpty().lowercase()
            algorithm !in FONT_OBFUSCATION && !FONT_FILE.containsMatchIn(uri)
        }
        if (encrypted) throw drm()
    }

    private fun drm() = BookParseException(ParseError.DRM, "This book is DRM-protected and can't be opened")

    fun firstSpineDocument(): String? {
        val container = zip.read("META-INF/container.xml")?.let { XmlNode.parse(TextDecoder.decode(it)) }
        val opfPath = container?.find("rootfile")?.get("full-path") ?: zip.entries.firstOrNull { it.endsWith(".opf", true) } ?: return null
        val opf = zip.read(opfPath)?.let { XmlNode.parse(TextDecoder.decode(it)) } ?: return null
        val items = opf.find("manifest")?.children("item").orEmpty().associateBy { it["id"] }
        val first = opf.find("spine")?.children("itemref")?.firstNotNullOfOrNull { items[it["idref"]]?.get("href") } ?: return null
        return PathUtil.resolve(opfPath, first)
    }

    fun firstImageIn(path: String): String? = zip.read(path)?.let { firstImage(TextDecoder.decode(it), path) }

    private fun findCover(metas: List<XmlNode>, manifest: Map<String, Item>, opf: XmlNode, opfPath: String): String? {
        manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }?.let { return it.href }
        metas.firstOrNull { it["name"] == "cover" }?.get("content")?.let { id ->
            manifest[id]?.let { if (it.mediaType.startsWith("image")) return it.href }
            manifest.values.firstOrNull { it.href.endsWith(id) && it.mediaType.startsWith("image") }?.let { return it.href }
        }
        opf.find("guide")?.children("reference")?.firstOrNull { it["type"]?.contains("cover", true) == true }?.get("href")?.let { href ->
            val path = PathUtil.resolve(opfPath, href.substringBefore('#'))
            if (path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) || path.endsWith(".png", true)) return path
            zip.read(path)?.let { bytes -> firstImage(TextDecoder.decode(bytes), path)?.let { return it } }
        }
        manifest.values.firstOrNull { it.mediaType.startsWith("image") && (it.id.contains("cover", true) || it.href.contains("cover", true)) }?.let { return it.href }
        return null
    }

    private fun firstImage(html: String, base: String): String? {
        val root = XmlNode.parse(html)
        val img = root.find("img") ?: root.find("image") ?: return null
        val src = img["src"] ?: img["href"] ?: return null
        return PathUtil.resolve(base, src)
    }

    private fun parseNav(manifest: Map<String, Item>): List<Triple<String, String, Int>>? {
        val nav = manifest.values.firstOrNull { it.properties.split(' ').contains("nav") } ?: return null
        val html = zip.read(nav.href)?.let { TextDecoder.decode(it) } ?: return null
        val root = XmlNode.parse(html)
        val navs = root.findAll("nav")
        val tocNav = navs.firstOrNull { it["type"]?.contains("toc") == true } ?: navs.firstOrNull() ?: return null
        val out = ArrayList<Triple<String, String, Int>>()
        fun walk(list: XmlNode, depth: Int) {
            for (li in list.children("li")) {
                val a = li.child("a") ?: li.child("span")
                val href = a?.get("href")
                val label = a?.text?.replace(WS, " ")?.trim().orEmpty()
                if (href != null && label.isNotEmpty()) {
                    val file = href.substringBefore('#')
                    val frag = href.substringAfter('#', "")
                    val resolved = if (file.isEmpty()) nav.href else PathUtil.resolve(nav.href, file)
                    out.add(Triple(if (frag.isEmpty()) resolved else "$resolved#$frag", label, depth))
                }
                li.child("ol")?.let { walk(it, depth + 1) }
                li.child("ul")?.let { walk(it, depth + 1) }
            }
        }
        val ol = tocNav.find("ol") ?: tocNav.find("ul") ?: return null
        walk(ol, 0)
        return out.ifEmpty { null }
    }

    private fun parseNcx(tocId: String?, manifest: Map<String, Item>): List<Triple<String, String, Int>>? {
        val ncx = (tocId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" || it.href.endsWith(".ncx", true) }) ?: return null
        val xml = zip.read(ncx.href)?.let { TextDecoder.decode(it) } ?: return null
        val root = XmlNode.parse(xml)
        val navMap = root.find("navmap") ?: return null
        val out = ArrayList<Triple<String, String, Int>>()
        fun walk(node: XmlNode, depth: Int) {
            for (p in node.children("navpoint")) {
                val label = p.child("navlabel")?.text?.replace(WS, " ")?.trim().orEmpty()
                val src = p.child("content")?.get("src")
                if (src != null && label.isNotEmpty()) {
                    val file = PathUtil.resolve(ncx.href, src.substringBefore('#'))
                    val frag = src.substringAfter('#', "")
                    out.add(Triple(if (frag.isEmpty()) file else "$file#$frag", label, depth))
                }
                walk(p, depth + 1)
            }
        }
        walk(navMap, 0)
        return out.ifEmpty { null }
    }

    companion object {
        private val WS = Regex("\\s+")
        private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
        private val FONT_FILE = Regex("\\.(otf|ttf|woff2?)$")

        fun stripTags(s: String): String = Entities.decode(CoverImages.stripTags(s)).replace(WS, " ").trim()
    }
}
