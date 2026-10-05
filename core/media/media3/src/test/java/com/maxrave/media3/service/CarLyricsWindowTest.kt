package com.maxrave.media3.service

import com.maxrave.domain.data.model.metadata.Line
import org.junit.Assert.*
import org.junit.Test

class CarLyricsWindowTest {
    private fun line(time: String, words: String) = Line("0", time, null, words)
    private val lyrics = listOf(line("1000", "First"), line("2000", "Second"), line("3000", ""), line("4000", "Last"))

    @Test fun introAndInstrumentalGapUseOriginalMetadata() {
        assertNull(lyricWindow(lyrics, 999))
        assertNull(lyricWindow(lyrics, 3500))
    }
    @Test fun exactBoundaryAndSeekSelectCurrentAndNextLine() {
        assertEquals("First" to "Second", lyricWindow(lyrics, 1000))
        assertEquals("Second" to "", lyricWindow(lyrics, 2500))
        assertEquals("First" to "Second", lyricWindow(lyrics, 1500))
        assertEquals("Last" to "", lyricWindow(lyrics, 4500))
    }
    @Test fun InvalidTimestampsAndUnsortedLinesAreHandled() {
        assertEquals("First" to "Second", lyricWindow(listOf(line("2000", "Second"), line("bad", "Ignored"), line("1000", "First")), 1500))
        assertNull(lyricWindow(emptyList(), 1000))
    }
    @Test fun offsetCanDelayLyricsUntilTheirTimestamp() {
        assertNull(lyricWindow(lyrics, 1200 - 500L))
        assertEquals("Second" to "", lyricWindow(lyrics, 1500 + 500L))
    }
}
