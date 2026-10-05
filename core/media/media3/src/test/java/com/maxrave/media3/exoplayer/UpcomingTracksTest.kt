package com.maxrave.media3.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpcomingTracksTest {
    @Test
    fun shufflePrefetchesTheActualNextItemsInsteadOfNumericallyAdjacentOnes() {
        assertEquals(listOf(0, 2), upcomingTrackIndices(listOf(3, 1, 0, 2), 1, 2, false, false))
    }

    @Test
    fun repeatAllWrapsWithoutPrefetchingTheCurrentTrackOrDuplicatingAnItem() {
        assertEquals(listOf(0, 1), upcomingTrackIndices(listOf(0, 1, 2), 2, 5, true, false))
    }

    @Test
    fun repeatOffAtTheEndAndRepeatOneDoNotOpenUnneededPlayers() {
        assertTrue(upcomingTrackIndices(listOf(2, 0, 1), 1, 2, false, false).isEmpty())
        assertTrue(upcomingTrackIndices(listOf(2, 0, 1), 2, 2, false, true).isEmpty())
    }

    @Test
    fun missingCurrentTrackAndEmptyQueueDoNotProduceAnIndex() {
        assertTrue(upcomingTrackIndices(emptyList(), 0, 2, true, false).isEmpty())
        assertTrue(upcomingTrackIndices(listOf(0, 1), -1, 2, false, false).isEmpty())
    }
}
