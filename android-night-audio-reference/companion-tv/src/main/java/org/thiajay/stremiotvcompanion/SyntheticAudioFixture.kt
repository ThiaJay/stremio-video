package org.thiajay.stremiotvcompanion

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** Creates only a local, deterministic WAV fixture; never stores user programme data. */
object SyntheticAudioFixture {
    private const val RATE = 48000
    private const val SECONDS = 12
    private const val CHANNELS = 2
    private const val BYTES_PER_FRAME = 4
    private const val TOTAL_AUDIO_BYTES = RATE * SECONDS * BYTES_PER_FRAME
    private const val TOTAL_BYTES = 44 + TOTAL_AUDIO_BYTES

    fun createIfMissing(cache: File): File {
        val target = File(cache, "stremio-companion-synthetic-sample.wav")
        if (target.isFile && target.length() == TOTAL_BYTES.toLong()) return target
        val temp = File(cache, "stremio-companion-synthetic-sample.tmp")
        try {
            BufferedOutputStream(FileOutputStream(temp)).use { out ->
                out.write("RIFF".toByteArray(Charsets.US_ASCII))
                out.write32(36 + TOTAL_AUDIO_BYTES)
                out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                out.write32(16)
                out.write16(1)  // PCM
                out.write16(CHANNELS)
                out.write32(RATE)
                out.write32(RATE * BYTES_PER_FRAME)
                out.write16(BYTES_PER_FRAME)
                out.write16(16)
                out.write("data".toByteArray(Charsets.US_ASCII))
                out.write32(TOTAL_AUDIO_BYTES)
                for (frame in 0 until RATE * SECONDS) {
                    val volume = if ((frame / (RATE * 3)) % 2 == 0) 0.035 else 0.55
                    val sample = (32767.0 * volume * kotlin.math.sin(2.0 * Math.PI * 330 * frame / RATE)).toInt()
                    out.write16(sample)
                    out.write16(sample)
                }
            }
            if (!temp.renameTo(target)) throw IllegalStateException("Local test fixture rename failed")
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
    private fun BufferedOutputStream.write16(x: Int) { write(x and 255); write((x ushr 8) and 255) }
    private fun BufferedOutputStream.write32(x: Int) {
        write(x and 255);write((x ushr 8) and 255);write((x ushr 16) and 255);write((x ushr 24) and 255)
    }
}
