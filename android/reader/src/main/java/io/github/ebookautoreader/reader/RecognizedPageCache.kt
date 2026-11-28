package io.github.ebookautoreader.reader

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object PageCacheKey {
    // Increment on any OCR, crop, layout or cache interpretation change.
    private const val REVISION = "recognized-page-v1-crop2-layout1"
    fun digest(width: Int, height: Int, bodyTop: Int, model: ByteArray): MessageDigest =
        MessageDigest.getInstance("SHA-256").apply {
            update(REVISION.toByteArray(Charsets.UTF_8))
            update(ByteBuffer.allocate(12).putInt(width).putInt(height).putInt(bodyTop).array())
            update(model)
        }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Debug-only store of successfully recognized pages. No images, audio, book
 * titles or exported text. Exact full-frame keys prevent a page-number-only hit.
 * Corruption, unsupported versions and storage failures are ordinary misses.
 */
internal data class PageCacheStats(val pages: Int, val bytes: Long, val temporaryFiles: Int)
internal data class PageCacheClear(val removed: Int, val failed: Int)

internal class RecognizedPageCache(private val directory: File) {
    companion object {
        const val DIRECTORY_NAME = "recognized-pages-v1"
        // UI and OCR create separate instances in the same process.
        private val lock = Any()
        private var epoch = 0L
        private const val MAGIC = 0x45525031
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val MAX_LINES = 4096
        private const val MAX_RUBY = 4096
        private val KEY = Regex("[0-9a-f]{64}")
    }

    private fun file(key: String): File? = if (KEY.matches(key)) directory.resolve("$key.page") else null

    private fun storedFiles(): List<File>? {
        if (!directory.exists()) return emptyList()
        return directory.listFiles()?.filter { file ->
            file.isFile && (file.name.endsWith(".page") && KEY.matches(file.name.removeSuffix(".page")) ||
                file.name.startsWith("page-") && file.name.endsWith(".tmp"))
        }
    }

    fun generation(): Long = synchronized(lock) { epoch }

    fun stats(): PageCacheStats? = synchronized(lock) {
        try {
            storedFiles()?.let { files ->
                PageCacheStats(files.count { it.name.endsWith(".page") },
                    files.sumOf { it.length() }, files.count { it.name.endsWith(".tmp") })
            }
        } catch (_: SecurityException) { null }
    }

    fun clear(): PageCacheClear? = synchronized(lock) {
        // Invalidate writes from OCR that started before this clear.
        epoch++
        try {
            storedFiles()?.let { files ->
                var removed = 0
                var failed = 0
                for (file in files) {
                    if (file.delete()) removed++ else failed++
                }
                PageCacheClear(removed, failed)
            }
        } catch (_: SecurityException) { null }
    }

    fun get(key: String): PageText? = synchronized(lock) { readLocked(key) }

    private fun readLocked(key: String): PageText? {
        val file = file(key) ?: return null
        if (!file.isFile) return null
        return try {
            require(file.length() in 33L..MAX_BYTES.toLong())
            val bytes = file.readBytes()
            require(bytes.size in 33..MAX_BYTES)
            val payload = bytes.copyOfRange(32, bytes.size)
            require(MessageDigest.isEqual(bytes.copyOfRange(0, 32),
                MessageDigest.getInstance("SHA-256").digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == MAGIC && input.readUTF() == key)
                val kind = PageKind.valueOf(input.readUTF())
                val count = input.readInt()
                require(count in 0..MAX_LINES)
                val lines = List(count) {
                    val original = input.readUTF()
                    val bounds = if (input.readBoolean())
                        LineBounds(input.readInt(), input.readInt(), input.readInt(), input.readInt()) else null
                    val rubyCount = input.readInt()
                    require(rubyCount in 0..MAX_RUBY)
                    val ruby = List(rubyCount) { Reading(input.readInt(), input.readInt(), input.readUTF()) }
                    val layout = if (input.readBoolean()) {
                        val type = input.readInt()
                        val detection = input.readFloat()
                        val certainty = input.readFloat()
                        val vertical = input.readBoolean()
                        val size = input.readInt()
                        val relative = if (input.readBoolean()) input.readFloat() else null
                        LineLayout(type, detection, certainty, vertical, size, relative, LineRole.valueOf(input.readUTF()))
                    } else null
                    SourceLine(original, ruby, bounds, layout)
                }
                require(input.read() == -1)
                PageText(lines, kind).also { require(valid(it)) }
            }
        } catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
        catch (_: SecurityException) { null }
    }

    fun put(key: String, page: PageText, expectedGeneration: Long? = null): Boolean =
        synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != epoch) false else writeLocked(key, page)
        }

    private fun writeLocked(key: String, page: PageText): Boolean {
        val file = file(key) ?: return false
        if (!valid(page)) return false
        var temp: File? = null
        return try {
            val payload = ByteArrayOutputStream()
            DataOutputStream(payload).use { output ->
                output.writeInt(MAGIC)
                output.writeUTF(key)
                output.writeUTF(page.kind.name)
                output.writeInt(page.lines.size)
                for (line in page.lines) {
                    output.writeUTF(line.original)
                    output.writeBoolean(line.bounds != null)
                    line.bounds?.let {
                        output.writeInt(it.left); output.writeInt(it.top)
                        output.writeInt(it.right); output.writeInt(it.bottom)
                    }
                    output.writeInt(line.ruby.size)
                    for (ruby in line.ruby) {
                        output.writeInt(ruby.start); output.writeInt(ruby.end); output.writeUTF(ruby.kana)
                    }
                    output.writeBoolean(line.layout != null)
                    line.layout?.let {
                        output.writeInt(it.detectorType); output.writeFloat(it.detection); output.writeFloat(it.certainty)
                        output.writeBoolean(it.vertical); output.writeInt(it.estimatedSizePx)
                        output.writeBoolean(it.relativeSize != null)
                        it.relativeSize?.let(output::writeFloat)
                        output.writeUTF(it.role.name)
                    }
                    if (payload.size() > MAX_BYTES - 32) return false
                }
            }
            val bytes = payload.toByteArray()
            if (!directory.isDirectory && !directory.mkdirs()) return false
            val staging = File.createTempFile("page-", ".tmp", directory)
            temp = staging
            FileOutputStream(staging).use { output ->
                output.write(MessageDigest.getInstance("SHA-256").digest(bytes))
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (_: IOException) { false }
        catch (_: IllegalArgumentException) { false }
        catch (_: SecurityException) { false }
        finally { temp?.delete() }
    }

    private fun valid(page: PageText): Boolean {
        if (page.lines.size > MAX_LINES) return false
        if (page.kind in setOf(PageKind.BODY, PageKind.HEADING) && page.lines.isEmpty()) return false
        if (page.kind == PageKind.BLANK && page.lines.isNotEmpty()) return false
        return page.lines.all { line ->
            line.original.isNotBlank() && line.original.length <= 8192 && line.ruby.size <= MAX_RUBY &&
                (line.bounds?.let { it.left >= 0 && it.top >= 0 && it.right > it.left && it.bottom > it.top } != false) &&
                line.ruby.all { it.start >= 0 && it.end > it.start && it.end <= line.original.length && it.kana.isReading() } &&
                (line.layout?.let { it.detection.isFinite() && it.certainty.isFinite() &&
                    it.estimatedSizePx > 0 && (it.relativeSize?.let { ratio -> ratio.isFinite() && ratio > 0 } != false) } != false)
        }
    }
}
