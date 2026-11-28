package io.github.ebookautoreader.reader

import android.util.AtomicFile
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream

/** V1 (all saved) is read in place; V2 explicitly encodes deferred pages. */
internal object AcquisitionJournalCodec {
    private const val V1 = 0x45414331
    private const val V2 = 0x45414332

    fun read(input: DataInputStream): AcquisitionProgress {
        val magic = input.readInt()
        require(magic == V1 || magic == V2)
        val first = input.readInt()
        val last = input.readInt()
        val count = input.readInt()
        require(count in 0..1000)
        val progress = AcquisitionProgress(first, last, List(count) {
            val number = input.readInt()
            val exact = input.readUTF()
            if (magic == V1 || input.readBoolean())
                AcquiredPage(number, exact, input.readUTF())
            else AcquiredPage(number, exact, null, input.readUTF())
        })
        require(input.read() == -1)
        return progress
    }

    fun write(out: DataOutputStream, progress: AcquisitionProgress) {
        out.writeInt(V2); out.writeInt(progress.first); out.writeInt(progress.last)
        out.writeInt(progress.pages.size)
        progress.pages.forEach {
            out.writeInt(it.number); out.writeUTF(it.exactKey)
            out.writeBoolean(it.cacheKey != null)
            out.writeUTF(it.cacheKey ?: requireNotNull(it.failure))
        }
        out.flush()
    }
}

/** Only numeric positions, hashes and enumerated reasons; no separate text store.
 * All instances share a lock because AtomicFile itself is not thread-safe. */
internal class AcquisitionJournal(file: File) {
    companion object {
        const val NAME = "acquisition-trial-v1" // retain the existing checkpoint path
        private val lock = Any()
    }
    private val file = AtomicFile(file)
    fun load(): AcquisitionProgress? = synchronized(lock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else try {
            DataInputStream(file.openRead()).use(AcquisitionJournalCodec::read)
        } catch (_: Exception) {
            throw ReaderFailure("前回の取得記録を開けません。", "acquire_checkpoint_invalid")
        }
    }
    fun save(progress: AcquisitionProgress) = synchronized(lock) {
        val stream = file.startWrite()
        try {
            AcquisitionJournalCodec.write(DataOutputStream(stream), progress)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        if (load() != progress) throw ReaderFailure("取得記録を保存できません。", "acquire_checkpoint_write")
    }
    fun clear() = synchronized(lock) { file.delete() }
}
