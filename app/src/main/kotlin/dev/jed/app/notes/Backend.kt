package dev.jed.app.notes

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Raw byte access to one workspace root. Two implementations: a plain
 * directory ([FileBackend]) and a user-attached folder ([SafBackend]).
 * Listing skips dot-entries (trash, assets, temp files); direct reads and
 * writes by id may address them, which is how trash and restore work.
 */
interface NoteBackend {
    data class Entry(val id: String, val folder: String, val mtime: Long)

    fun listEntries(): List<Entry>
    fun read(id: String): String?
    fun mtime(id: String): Long
    fun exists(id: String): Boolean
    fun write(id: String, text: String): Boolean
    fun create(folder: String, filename: String, text: String): String?
    fun delete(id: String): Boolean
    fun mkdirs(folder: String): Boolean
    /** Raw bytes for the `.jed-assets/` image pool. */
    fun writeBytes(id: String, bytes: ByteArray): Boolean
    fun createBytes(folder: String, filename: String, mime: String, bytes: ByteArray): String?
}

class FileBackend(private val root: File) : NoteBackend {

    private fun file(id: String) = File(root, id)

    override fun listEntries(): List<NoteBackend.Entry> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { dir -> dir.name == root.name || !dir.name.startsWith(".") }
            .filter { it.isFile && it.extension == "md" && !it.name.startsWith(".") }
            .filter { f -> f.relativeTo(root).parentFile?.path?.split(File.separator)?.none { it.startsWith(".") } != false }
            .map {
                val rel = it.relativeTo(root).path.replace(File.separatorChar, '/')
                NoteBackend.Entry(rel, rel.substringBeforeLast('/', ""), it.lastModified())
            }.toList()
    }

    override fun read(id: String): String? = runCatching {
        file(id).takeIf { it.isFile }?.readText()
    }.getOrNull()

    override fun mtime(id: String): Long = file(id).lastModified()

    override fun exists(id: String): Boolean = file(id).isFile

    /** Temp-file-plus-rename: a crash leaves the old note or the new one. */
    override fun write(id: String, text: String): Boolean = runCatching {
        val dest = file(id)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, ".${dest.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(dest)
    }.getOrDefault(false)

    override fun create(folder: String, filename: String, text: String): String? {
        val id = if (folder.isEmpty()) filename else "$folder/$filename"
        return if (write(id, text)) id else null
    }

    override fun delete(id: String): Boolean = runCatching { file(id).delete() }.getOrDefault(false)

    override fun mkdirs(folder: String): Boolean =
        if (folder.isEmpty()) true else runCatching { File(root, folder).mkdirs() }.getOrDefault(false)

    override fun writeBytes(id: String, bytes: ByteArray): Boolean = runCatching {
        val dest = file(id)
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        true
    }.getOrDefault(false)

    override fun createBytes(folder: String, filename: String, mime: String, bytes: ByteArray): String? {
        val id = if (folder.isEmpty()) filename else "$folder/$filename"
        if (exists(id)) return null
        return if (writeBytes(id, bytes)) id else null
    }
}

class SafBackend(
    context: android.content.Context,
    treeUri: Uri,
) : NoteBackend {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val tree: DocumentFile? =
        DocumentFile.fromTreeUri(context.applicationContext, treeUri)

    private fun doc(id: String): DocumentFile? {
        var cur = tree ?: return null
        if (id.isEmpty()) return cur
        for (seg in id.split("/")) {
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    private fun ensureDir(folder: String): DocumentFile? {
        var cur = tree ?: return null
        if (folder.isEmpty()) return cur
        for (seg in folder.split("/")) {
            cur = cur.findFile(seg)?.takeIf { it.isDirectory }
                ?: cur.createDirectory(seg) ?: return null
        }
        return cur
    }

    private fun walk(dir: DocumentFile, folder: String, out: MutableList<NoteBackend.Entry>) {
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (name.startsWith(".")) continue
            if (f.isDirectory) {
                val sub = if (folder.isEmpty()) name else "$folder/$name"
                walk(f, sub, out)
            } else if (f.isFile && name.endsWith(NoteFiles.EXT)) {
                val id = if (folder.isEmpty()) name else "$folder/$name"
                out += NoteBackend.Entry(id, folder, f.lastModified())
            }
        }
    }

    override fun listEntries(): List<NoteBackend.Entry> {
        val t = tree ?: return emptyList()
        return mutableListOf<NoteBackend.Entry>().also { walk(t, "", it) }
    }

    override fun read(id: String): String? = runCatching {
        val d = doc(id)?.takeIf { it.isFile } ?: return null
        resolver.openInputStream(d.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    override fun mtime(id: String): Long = doc(id)?.lastModified() ?: 0L

    override fun exists(id: String): Boolean = doc(id)?.isFile == true

    override fun write(id: String, text: String): Boolean = runCatching {
        val d = doc(id)?.takeIf { it.isFile } ?: return false
        resolver.openOutputStream(d.uri, "wt")?.use { it.write(text.toByteArray()) }
        true
    }.getOrDefault(false)

    override fun create(folder: String, filename: String, text: String): String? = runCatching {
        val dir = ensureDir(folder) ?: return null
        if (dir.findFile(filename)?.isFile == true) return null
        val d = dir.createFile("text/markdown", filename) ?: return null
        resolver.openOutputStream(d.uri, "wt")?.use { it.write(text.toByteArray()) }
        if (folder.isEmpty()) filename else "$folder/$filename"
    }.getOrNull()

    override fun delete(id: String): Boolean = runCatching {
        doc(id)?.delete() == true
    }.getOrDefault(false)

    override fun mkdirs(folder: String): Boolean = ensureDir(folder) != null

    override fun writeBytes(id: String, bytes: ByteArray): Boolean = runCatching {
        val d = doc(id)?.takeIf { it.isFile } ?: return false
        resolver.openOutputStream(d.uri, "wt")?.use { it.write(bytes) }
        true
    }.getOrDefault(false)

    override fun createBytes(folder: String, filename: String, mime: String, bytes: ByteArray): String? = runCatching {
        val dir = ensureDir(folder) ?: return null
        if (dir.findFile(filename)?.isFile == true) return null
        val d = dir.createFile(mime, filename) ?: return null
        resolver.openOutputStream(d.uri, "wt")?.use { it.write(bytes) }
        if (folder.isEmpty()) filename else "$folder/$filename"
    }.getOrNull()
}
