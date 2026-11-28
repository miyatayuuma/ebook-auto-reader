package io.github.ebookautoreader.reader

import kotlinx.coroutines.CancellationException
import java.io.Closeable

/** In-memory one-page startup payload. Never a navigation authority. The
 * original image is released before speech synthesis; a fresh capture must
 * match the exact pixel identity before the result may enter the session.
 */
internal class PreparedStart private constructor(
    private val key: String,
    val page: PageText,
    private val first: ReadingUnit?,
    private var clip: ReaderClip?,
) : Closeable {
    val hasClip get() = clip != null

    suspend fun matches(frame: PageFrame): Boolean = frame.exactKey() == key

    fun takeClip(unit: ReadingUnit): ReaderClip? {
        if (first != unit) {
            discardAudio()
            return null
        }
        return clip.also { clip = null }
    }

    fun discardAudio() {
        val previous = clip
        clip = null
        previous?.close()
    }

    override fun close() = discardAudio()

    companion object {
        suspend fun prepare(
            pages: ReaderPages, audio: ReaderAudio, pronunciations: Pronunciations,
            log: (String) -> Unit = {},
        ): PreparedStart? {
            val (key, page) = pages.capture().use { frame ->
                val key = frame.exactKey() ?: return null
                key to pages.read(frame)
            }
            if (page.kind in listOf(PageKind.BODY, PageKind.HEADING) &&
                page.lines.none { it.original.isNotBlank() }) return null
            val buffer = SentenceBuffer(pronunciations)
            buffer.add(0, page)
            // No flush: a sentence continuing to the next page is not complete.
            val first = buffer.poll()
            val clip = if (first == null) null else try {
                audio.synthesize(first).let {
                    if (it.bytes > MAX_CLIP_BYTES || it.durationMs <= 0) {
                        it.close()
                        null
                    } else it
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("prewarm audio unavailable type=${error.javaClass.simpleName}")
                null
            }
            return PreparedStart(key, page, first, clip)
        }
    }
}
