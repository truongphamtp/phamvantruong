package io.github.truongphamtp.docsach.format

import io.github.truongphamtp.docsach.core.ReadingPosition
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Truy cập các tệp bên trong một file EPUB (thực chất là file ZIP). */
class EpubArchive(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val names: Map<String, String> = buildMap {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory) put(entry.name.lowercase(), entry.name)
        }
    }

    val entryNames: Collection<String> get() = names.values

    private fun entryName(path: String): String? =
        if (zip.getEntry(path) != null) path else names[path.lowercase()]

    fun exists(path: String): Boolean = entryName(path) != null

    fun read(path: String): ByteArray? {
        val name = entryName(path) ?: return null
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun readText(path: String): String? = read(path)?.let(::decodeMarkup)

    override fun close() = zip.close()
}

data class EpubMetadata(val title: String, val author: String, val coverPath: String?)

/** Giải mã văn bản XML/XHTML theo BOM hoặc khai báo encoding, mặc định UTF-8. */
internal fun decodeMarkup(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
    val declared = Regex("encoding=[\"']([A-Za-z0-9._-]+)[\"']").find(head)?.groupValues?.get(1)
    val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    return String(bytes, charset)
}

/** Ghép đường dẫn tương đối [href] với thư mục [baseDir] bên trong EPUB. */
internal fun resolvePath(baseDir: String, href: String): String {
    val clean = href.substringBefore('#').substringBefore('?').trim()
    val decoded = runCatching { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") }.getOrDefault(clean)
    val joined = when {
        decoded.startsWith("/") -> decoded.drop(1)
        baseDir.isEmpty() -> decoded
        else -> "$baseDir/$decoded"
    }
    val parts = ArrayList<String>()
    for (part in joined.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += part
        }
    }
    return parts.joinToString("/")
}

private fun parseXml(text: String): Document = Jsoup.parse(text, "", Parser.xmlParser())

object EpubParser {
    private const val CONTAINER = "META-INF/container.xml"

    private class Item(val id: String, val path: String, val mediaType: String, val properties: List<String>)

    private class Package(
        val title: String,
        val author: String,
        val spine: List<Item>,
        val navPath: String?,
        val ncxPath: String?,
        val coverPath: String?,
        /** Điểm "bắt đầu đọc" khai báo trong <guide> (EPUB 2, Kindle dùng). */
        val guideStart: String?,
    )

    /** Kiểm tra nhanh một file ZIP có phải EPUB không. */
    fun isEpub(file: File): Boolean = runCatching {
        ZipFile(file).use { zip -> zip.getEntry(CONTAINER) != null || zip.getEntry("mimetype") != null }
    }.getOrDefault(false)

    fun readMetadata(archive: EpubArchive): EpubMetadata {
        val pkg = readPackage(archive)
        return EpubMetadata(pkg.title, pkg.author, pkg.coverPath)
    }

    private fun readPackage(archive: EpubArchive): Package {
        val opfPath = archive.readText(CONTAINER)
            ?.let { parseXml(it) }
            ?.getAllElements()
            ?.firstOrNull { it.localName() == "rootfile" && it.attr("full-path").isNotBlank() }
            ?.attr("full-path")
            ?: archive.entryNames.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: throw IOException("Không tìm thấy mục lục gói (OPF) trong file EPUB")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = parseXml(archive.readText(opfPath) ?: throw IOException("Không đọc được $opfPath"))
        val all = opf.getAllElements()

        val title = all.firstOrNull { it.localName() == "title" }?.text()?.trim().orEmpty()
        val author = all.filter { it.localName() == "creator" }
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")

        val manifest = LinkedHashMap<String, Item>()
        for (el in all) {
            if (el.localName() != "item" || !el.hasAttr("href")) continue
            val id = el.attr("id")
            manifest[id] = Item(
                id = id,
                path = resolvePath(opfDir, el.attr("href")),
                mediaType = el.attr("media-type").lowercase(),
                properties = el.attr("properties").split(' ').filter { it.isNotBlank() },
            )
        }

        val spineEl = all.firstOrNull { it.localName() == "spine" }
        val spine = all.filter { it.localName() == "itemref" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") || it.mediaType.contains("xml") || it.path.endsWith("htm", true) }
            .filter { it.mediaType != "application/x-dtbncx+xml" }

        val navPath = manifest.values.firstOrNull { "nav" in it.properties }?.path
        val ncxPath = (spineEl?.attr("toc")?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })?.path

        val images = manifest.values.filter { it.mediaType.startsWith("image/") }
        val coverMetaId = all.firstOrNull { it.localName() == "meta" && it.attr("name") == "cover" }?.attr("content")
        val coverPath = (images.firstOrNull { "cover-image" in it.properties }
            ?: coverMetaId?.let { id -> manifest[id]?.takeIf { it.mediaType.startsWith("image/") } }
            ?: images.firstOrNull { it.id.contains("cover", true) || it.path.contains("cover", true) })?.path

        val guideStart = all.firstOrNull { el ->
            el.localName() == "reference" && el.attr("type").lowercase() in setOf("text", "start", "bodymatter")
        }?.attr("href")?.takeIf { it.isNotBlank() }?.let { href ->
            val fragment = href.substringAfter('#', "")
            resolvePath(opfDir, href) + if (fragment.isNotEmpty()) "#$fragment" else ""
        }

        return Package(title, author, spine, navPath, ncxPath, coverPath, guideStart)
    }

    /** Thông tin để đổi liên kết "tệp#id" thành vị trí (chương, ký tự). */
    private class ItemIndex(val firstChapter: Int, val segmentStarts: IntArray, val anchors: Map<String, Int>)

    /** Đọc toàn bộ nội dung sách. */
    fun parse(archive: EpubArchive, fallbackTitle: String): BookContent {
        val pkg = readPackage(archive)
        val chapters = ArrayList<Chapter>()
        val items = HashMap<String, ItemIndex>()
        val cssCache = HashMap<String, String>()
        for (item in pkg.spine) {
            val html = archive.readText(item.path)
            if (html == null) {
                items[item.path] = ItemIndex(chapters.size, intArrayOf(0), emptyMap())
                continue
            }
            val doc = parseXml(html)
            val dir = item.path.substringBeforeLast('/', "")
            val sheet = StyleSheet()
            for (el in doc.getAllElements()) {
                when (el.localName()) {
                    "link" -> if (el.attr("rel").contains("stylesheet", ignoreCase = true)) {
                        val cssPath = resolvePath(dir, el.attr("href"))
                        val css = cssCache.getOrPut(cssPath) { archive.readText(cssPath).orEmpty() }
                        sheet.addCss(css)
                    }
                    "style" -> sheet.addCss(el.wholeText())
                }
            }
            val body = doc.getAllElements().firstOrNull { it.localName() == "body" } ?: doc
            val builder = collectBlocks(body, sheet) { src ->
                resolvePath(dir, src).takeIf { archive.exists(it) }
            }
            val segments = splitLargeItem(builder.blocks)
            if (segments.isEmpty()) {
                // Tệp rỗng: liên kết tới nó sẽ trỏ sang chương kế tiếp.
                items[item.path] = ItemIndex(chapters.size, intArrayOf(0), emptyMap())
                continue
            }
            items[item.path] = ItemIndex(chapters.size, segments.map { it.first().start }.toIntArray(), builder.anchors)
            for (segment in segments) {
                val base = segment.first().start
                val blocks = segment.map { it.movedTo(it.start - base) }
                val end = base + blocks.last().end
                val anchors = builder.anchors.filterValues { it in base until end }.mapValues { it.value - base }
                val heading = blocks.firstOrNull { it is TextBlock && it.kind.isHeading } as TextBlock?
                chapters += Chapter(heading?.text?.replace('\n', ' ').orEmpty(), blocks, anchors, item.path)
            }
        }
        if (chapters.isEmpty()) {
            chapters += Chapter(
                "",
                listOf(TextBlock(0, "Không tìm thấy nội dung có thể hiển thị trong cuốn sách này.", BlockKind.PARAGRAPH)),
            )
        }

        fun target(href: String, baseDir: String): Pair<Int, Int>? {
            val path = resolvePath(baseDir, href)
            val index = items[path] ?: return null
            val fragment = href.substringAfter('#', "")
            val itemOffset = if (fragment.isEmpty()) 0 else index.anchors[fragment] ?: 0
            var segment = 0
            while (segment + 1 < index.segmentStarts.size && index.segmentStarts[segment + 1] <= itemOffset) segment++
            val chapter = (index.firstChapter + segment).coerceAtMost(chapters.size - 1)
            val local = if (index.firstChapter + segment > chapters.size - 1) 0 else itemOffset - index.segmentStarts[segment]
            return chapter to local.coerceAtLeast(0)
        }

        var toc = pkg.navPath?.let { parseNav(archive, it, ::target) }.orEmpty()
        if (toc.isEmpty()) toc = pkg.ncxPath?.let { parseNcx(archive, it, ::target) }.orEmpty()

        val titled = chapters.mapIndexed { index, chapter ->
            val entries = toc.filter { it.chapter == index }
            val title = entries.firstOrNull { it.offset == 0 }?.title
                ?: chapter.title.takeIf { it.isNotBlank() }
                ?: entries.minByOrNull { it.offset }?.title
                ?: ""
            if (title == chapter.title) chapter else Chapter(title, chapter.blocks, chapter.anchors, chapter.path)
        }

        val startHref = pkg.navPath?.let { parseLandmarkStart(archive, it) } ?: pkg.guideStart
        val start = startHref?.let { target(it, "") }
            ?.takeIf { it.first > 0 || it.second > 0 }
            ?.let { (chapter, offset) -> ReadingPosition(chapter, offset) }

        val title = pkg.title.ifBlank { fallbackTitle }
        return BookContent(title, pkg.author, titled, toc, start)
    }

    /**
     * Tệp XHTML rất dài (ví dụ Google Docs xuất cả cuốn vào một tệp) được tách thành nhiều chương
     * tại chỗ ngắt trang hoặc tiêu đề lớn, để mở sách và dàn trang nhanh hơn.
     */
    private fun splitLargeItem(blocks: List<Block>): List<List<Block>> {
        if (blocks.isEmpty()) return emptyList()
        val total = blocks.last().end
        if (total < SPLIT_THRESHOLD) return listOf(blocks)
        val hasH1 = blocks.any { it is TextBlock && it.kind == BlockKind.HEADING1 }
        val splitKind = if (hasH1) BlockKind.HEADING1 else BlockKind.HEADING2
        val segments = ArrayList<List<Block>>()
        var current = ArrayList<Block>()
        var currentStart = 0
        for (block in blocks) {
            val isBoundary = block.pageBreakBefore || (block is TextBlock && block.kind == splitKind)
            if (isBoundary && current.isNotEmpty() && block.start - currentStart >= MIN_SEGMENT) {
                segments += current
                current = ArrayList()
                currentStart = block.start
            }
            current += block
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    private const val SPLIT_THRESHOLD = 40_000
    private const val MIN_SEGMENT = 3_000

    /** EPUB 3: <nav epub:type="landmarks"> ... <a epub:type="bodymatter">. */
    private fun parseLandmarkStart(archive: EpubArchive, navPath: String): String? {
        val doc = parseXml(archive.readText(navPath) ?: return null)
        val landmarks = doc.getAllElements().firstOrNull { it.localName() == "nav" && "landmarks" in it.attr("epub:type") }
            ?: return null
        val link = landmarks.getAllElements().firstOrNull { el ->
            el.localName() == "a" && el.attr("epub:type").split(' ').any { it == "bodymatter" || it == "text" }
        } ?: return null
        val navDir = navPath.substringBeforeLast('/', "")
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fragment = href.substringAfter('#', "")
        return resolvePath(navDir, href) + if (fragment.isNotEmpty()) "#$fragment" else ""
    }

    private fun parseNav(
        archive: EpubArchive,
        navPath: String,
        target: (String, String) -> Pair<Int, Int>?,
    ): List<TocEntry> {
        val text = archive.readText(navPath) ?: return emptyList()
        val doc = parseXml(text)
        val navDir = navPath.substringBeforeLast('/', "")
        val navs = doc.getAllElements().filter { it.localName() == "nav" }
        val tocNav = navs.firstOrNull { it.attr("epub:type").contains("toc") } ?: navs.firstOrNull() ?: return emptyList()
        val rootList = tocNav.getAllElements().firstOrNull { it.localName() == "ol" || it.localName() == "ul" }
            ?: return emptyList()
        val out = ArrayList<TocEntry>()
        fun walk(list: Element, depth: Int) {
            for (li in list.children()) {
                if (li.localName() != "li") continue
                val link = li.children().firstOrNull { it.localName() == "a" || it.localName() == "span" }
                val title = link?.text()?.trim().orEmpty()
                val href = link?.attr("href").orEmpty()
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    target(href, navDir)?.let { (chapter, offset) -> out += TocEntry(title, chapter, offset, depth) }
                }
                li.children().firstOrNull { it.localName() == "ol" || it.localName() == "ul" }?.let { walk(it, depth + 1) }
            }
        }
        walk(rootList, 0)
        return out
    }

    private fun parseNcx(
        archive: EpubArchive,
        ncxPath: String,
        target: (String, String) -> Pair<Int, Int>?,
    ): List<TocEntry> {
        val text = archive.readText(ncxPath) ?: return emptyList()
        val doc = parseXml(text)
        val ncxDir = ncxPath.substringBeforeLast('/', "")
        val navMap = doc.getAllElements().firstOrNull { it.localName() == "navmap" } ?: return emptyList()
        val out = ArrayList<TocEntry>()
        fun walk(parent: Element, depth: Int) {
            for (point in parent.children()) {
                if (point.localName() != "navpoint") continue
                val label = point.children().firstOrNull { it.localName() == "navlabel" }?.text()?.trim().orEmpty()
                val src = point.children().firstOrNull { it.localName() == "content" }?.attr("src").orEmpty()
                if (label.isNotEmpty() && src.isNotEmpty()) {
                    target(src, ncxDir)?.let { (chapter, offset) -> out += TocEntry(label, chapter, offset, depth) }
                }
                walk(point, depth + 1)
            }
        }
        walk(navMap, 0)
        return out
    }
}
