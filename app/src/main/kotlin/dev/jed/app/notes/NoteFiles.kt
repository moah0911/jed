package dev.jed.app.notes

/**
 * Pure note mechanics for Jed: filenames, frontmatter, fenced blocks, tags,
 * wikilinks and search. Deliberately free of Android imports so the JVM unit
 * tests exercise the same code the app runs. Notes are plain `.md` files;
 * the view never names a file, it sends text and this module slugs the H1.
 */
object NoteFiles {

    const val EXT = ".md"
    const val TRASH_DIR = ".jed-trash"
    const val ASSETS_DIR = ".jed-assets"
    const val MAX_HITS = 200

    /** A fenced code block and where it sits in the note. */
    data class CodeBlock(
        val lang: String,
        val code: String,
        val startLine: Int,
        /** First code line, in document lines. */
        val codeStart: Int = -1,
        /** One past the last code line, in document lines. */
        val codeEnd: Int = -1,
        /** The closing fence's line, or null for an unclosed block. */
        val closeLine: Int? = null,
    ) {
        val unclosed: Boolean get() = closeLine == null
    }

    /** Which on-device interpreter runs a fence, or null for render-only. */
    enum class Runner { SHELL, PYTHON }

    fun runnerFor(lang: String): Runner? = when (lang.trim().lowercase()) {
        "sh", "shell", "bash", "zsh", "dash", "ksh" -> Runner.SHELL
        "python", "py", "py3" -> Runner.PYTHON
        else -> null
    }

    /** Short human reason a fence has no Run button. */
    fun unrunnableNote(lang: String): String =
        if (lang.isEmpty()) "A fenced block with no language cannot run."
        else "Only sh and python blocks run on this device."

    /** Frontmatter parsed from a leading `---` block. */
    data class Frontmatter(
        val cwd: String = "",
        val env: Map<String, String> = emptyMap(),
        val profile: String = "",
        val tags: List<String> = emptyList(),
        val template: Boolean = false,
    )

    /** Filename-safe slug built from a note's H1. Emits only [a-z0-9-]. */
    fun slugOf(heading: String): String {
        val slug = heading.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
        return slug.ifEmpty { "untitled" }
    }

    /**
     * Allocate a filename against taken names (case-insensitive, APFS-style).
     * The rename that follows is safe because the name was reserved here.
     */
    fun uniqueName(base: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        var name = "$base$EXT"
        var n = 2
        while (name.lowercase() in lower) {
            name = "$base-$n$EXT"
            n += 1
        }
        return name
    }

    /** The note's title: its first `# ` heading, else first text line. */
    fun titleOf(text: String): String {
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("# ")) return t.removePrefix("# ").trim().ifEmpty { "Untitled" }
        }
        for (line in text.lines()) {
            val t = line.trim().trimStart('#', ' ').trim()
            if (t.isNotEmpty()) return t.take(120)
        }
        return "Untitled"
    }

    /** Split leading `---` frontmatter from the body. */
    fun splitFrontmatter(text: String): Pair<Frontmatter, String> {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") return Frontmatter() to text
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return Frontmatter() to text
        val head = lines.drop(1).take(end)
        val body = lines.drop(1 + end + 1).joinToString("\n")
        var cwd = ""
        var profile = ""
        var template = false
        val env = LinkedHashMap<String, String>()
        val tags = mutableListOf<String>()
        var inEnv = false
        for (raw in head) {
            val line = raw.trim()
            if (line.startsWith("env:")) {
                inEnv = true
                continue
            }
            if (inEnv && (raw.startsWith(" ") || raw.startsWith("\t"))) {
                val sep = when {
                    "=" in line -> "="
                    ":" in line -> ":"
                    else -> null
                }
                if (sep != null) {
                    val k = line.substringBefore(sep).trim()
                    val v = line.substringAfter(sep).trim()
                    if (k.isNotEmpty()) env[k] = v
                    continue
                }
            }
            inEnv = false
            when {
                line.startsWith("cwd:") -> cwd = line.removePrefix("cwd:").trim()
                line.startsWith("profile:") -> profile = line.removePrefix("profile:").trim()
                line.startsWith("template:") -> template = line.removePrefix("template:").trim() == "true"
                line.startsWith("tags:") -> tags += line.removePrefix("tags:").split(',', ' ').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
            }
        }
        return Frontmatter(cwd, env, profile, tags, template) to body
    }

    /** All fenced blocks in document order. A trailing unclosed fence counts,
     * so a half-typed block still offers Run instead of vanishing. */
    fun parseFences(text: String): List<CodeBlock> {
        val lines = text.lines()
        val out = mutableListOf<CodeBlock>()
        var lang = ""
        var start = 0
        var buf: StringBuilder? = null
        lines.forEachIndexed { i, line ->
            val t = line.trimStart()
            if (t.startsWith("```")) {
                if (buf == null) {
                    lang = t.removePrefix("```").trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                    start = i
                    buf = StringBuilder()
                } else {
                    out += CodeBlock(lang, buf.toString(), start, start + 1, i, i)
                    buf = null
                }
            } else {
                buf?.appendLine(line)
            }
        }
        if (buf != null) {
            out += CodeBlock(lang, buf.toString(), start, start + 1, lines.size, null)
        }
        return out
    }

    /** Inline `#tags` (headings excluded) plus frontmatter tags. */
    fun tagsOf(text: String): List<String> {
        val (fm, body) = splitFrontmatter(text)
        val found = LinkedHashSet<String>()
        found += fm.tags
        for (line in body.lines()) {
            val t = line.trimStart()
            if (t.startsWith("#")) continue // heading, not a tag
            Regex("(?<=^|[\\s(\\[])#([A-Za-z0-9][A-Za-z0-9_/-]*)").findAll(line).forEach {
                found += it.groupValues[1]
            }
        }
        return found.toList()
    }

    /** `[[Title]]` and `[[Title#heading]]` targets in document order. */
    fun wikilinksOf(text: String): List<String> {
        val (_, body) = splitFrontmatter(text)
        return Regex("\\[\\[([^\\]]+)]]").findAll(body)
            .map { it.groupValues[1].substringBefore("#").trim() }
            .filter { it.isNotEmpty() }.toList()
    }

    /**
     * Resolve a wikilink title against known notes: exact match first
     * (case-insensitive), newest first on ambiguity. Titles, never paths.
     */
    fun resolveTitle(title: String, notes: List<NoteMeta>): NoteMeta? {
        val want = title.trim().lowercase()
        return notes.filter { it.title.lowercase() == want }.maxByOrNull { it.mtime }
            ?: notes.filter { it.title.lowercase().startsWith(want) }.maxByOrNull { it.mtime }
    }

    /** One case-insensitive substring over title and body. */
    fun search(notes: List<NoteMeta>, bodies: Map<String, String>, query: String): List<NoteMeta> {
        val q = query.lowercase()
        if (q.isBlank()) return emptyList()
        return notes.filter {
            it.title.lowercase().contains(q) || (bodies[it.id]?.lowercase()?.contains(q) == true)
        }.sortedByDescending { it.mtime }.take(MAX_HITS)
    }

    /** A folder filter selects notes already found; it never becomes a path. */
    fun inFolder(note: NoteMeta, folder: String): Boolean {
        if (folder.isEmpty()) return true
        return note.folder == folder || note.folder.startsWith("$folder/")
    }

    /** Placement guard for a folder a caller names: relative, no dots. */
    fun folderProblem(folder: String): String? {
        if (folder.isEmpty()) return null
        if (folder.startsWith("/") || folder.contains("\\")) return "A folder is relative, without backslashes."
        val segs = folder.split("/")
        if (segs.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") }) {
            return "A folder has no empty, dot or hidden segments."
        }
        return null
    }

    // --- inline segments ------------------------------------------------------
    //
    // The editor renders a note as prose runs interleaved with fence cards in
    // document order, so Run sits where the code is instead of below a text
    // box. Segments hold exact lines and round-trip through buildText.

    /** One editable run: prose lines, or one fence's code lines. */
    data class Segment(val fence: CodeBlock?, val lines: List<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    /** Split into prose/fence runs. A trailing prose run always exists. */
    fun segmentize(text: String): List<Segment> {
        val lines = text.lines()
        val blocks = parseFences(text)
        val out = mutableListOf<Segment>()
        var at = 0
        for (b in blocks) {
            val end = b.closeLine?.plus(1) ?: lines.size
            if (b.startLine > at) {
                out += Segment(null, lines.subList(at, b.startLine).toList())
            }
            out += Segment(b, lines.subList(b.codeStart, b.codeEnd).toList())
            at = end
        }
        if (at < lines.size) {
            out += Segment(null, lines.subList(at, lines.size).toList())
        } else if (out.isEmpty() || out.last().fence != null) {
            out += Segment(null, emptyList())
        }
        return out
    }

    /** Rejoin segments exactly: fences regain their fences and language. */
    fun buildText(segments: List<Segment>): String {
        val lines = mutableListOf<String>()
        for (s in segments) {
            val f = s.fence
            if (f == null) {
                lines += s.lines
            } else {
                lines += "```" + f.lang
                lines += s.lines
                if (!f.unclosed) lines += "```"
            }
        }
        return lines.joinToString("\n")
    }
}

/** A note as the lists show it. The path is an opaque handle. */
data class NoteMeta(
    val id: String,
    val title: String,
    val folder: String,
    val mtime: Long,
    val tags: List<String> = emptyList(),
)
