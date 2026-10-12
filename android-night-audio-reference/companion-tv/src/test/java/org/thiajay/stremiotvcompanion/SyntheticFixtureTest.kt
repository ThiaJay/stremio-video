package org.thiajay.stremiotvcompanion

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SyntheticFixtureTest {
    @Test fun localWavHasCorrectPcmHeaderAndDurationAndIsIdempotent() {
        val dir = Files.createTempDirectory("companion-synthetic-").toFile()
        try {
            val file = SyntheticAudioFixture.createIfMissing(dir)
            val bytes = file.readBytes()
            assertEquals(44 + 12 * 48000 * 4, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0,4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", bytes.copyOfRange(8,12).toString(Charsets.US_ASCII))
            assertEquals("fmt ", bytes.copyOfRange(12,16).toString(Charsets.US_ASCII))
            assertEquals("data", bytes.copyOfRange(36,40).toString(Charsets.US_ASCII))
            assertEquals(file.absolutePath, SyntheticAudioFixture.createIfMissing(dir).absolutePath)
            assertEquals(bytes.size.toLong(), file.length())
        } finally { dir.deleteRecursively() }
    }
}
