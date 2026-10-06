package com.maxrave.media3.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class CaptionTimingTest {
    @Test fun cuesEndAtTheirEndTimeAndSeekBackToTheCorrectLine() {
        val cues = listOf(Triple(1000L, 2000L, "First"), Triple(3000L, 4000L, "Second"))
        assertEquals("", captionTextAt(cues, 999))
        assertEquals("First", captionTextAt(cues, 1000))
        assertEquals("", captionTextAt(cues, 2000))
        assertEquals("Second", captionTextAt(cues, 3500))
        assertEquals("First", captionTextAt(cues, 1500))
        assertEquals("", captionTextAt(cues, 4000))
    }
    @Test fun missingEndUsesTheNextCueBoundary() {
        val cues = listOf(Triple(0L, null, "First"), Triple(1000L, null, "Second"))
        assertEquals("First", captionTextAt(cues, 999))
        assertEquals("Second", captionTextAt(cues, 1000))
    }
}
