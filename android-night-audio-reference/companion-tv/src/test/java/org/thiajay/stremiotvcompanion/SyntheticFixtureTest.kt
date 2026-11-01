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

    @Test fun localWebVttHasDeterministicCuesAndIsIdempotent() {
        val dir = Files.createTempDirectory("companion-subtitles-").toFile()
        try {
            val file = SyntheticSubtitleFixture.createIfMissing(dir)
            val first = file.readText(Charsets.UTF_8)
            assertTrue(first.startsWith("WEBVTT\n\n"))
            assertEquals(3, Regex("\\d\\d:\\d\\d\\.\\d{3} --> ").findAll(first).count())
            assertTrue(first.contains("Synthetic subtitle cue one."))
            assertTrue(first.contains("Synthetic subtitle cue two."))
            assertTrue(first.contains("Synthetic subtitle cue three."))
            assertEquals(file.absolutePath, SyntheticSubtitleFixture.createIfMissing(dir).absolutePath)
            assertEquals(first, file.readText(Charsets.UTF_8))
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptLocalWebVttIsReplacedWithoutTouchingUnrelatedFiles() {
        val dir = Files.createTempDirectory("companion-subtitles-corrupt-").toFile()
        try {
            val file = SyntheticSubtitleFixture.createIfMissing(dir)
            val unrelated = dir.resolve("unrelated.txt").apply { writeText("preserved") }
            file.writeText("corrupt")

            val repaired = SyntheticSubtitleFixture.createIfMissing(dir)

            assertTrue(repaired.readText(Charsets.UTF_8).startsWith("WEBVTT\n\n"))
            assertEquals("preserved", unrelated.readText())
        } finally { dir.deleteRecursively() }
    }
}

