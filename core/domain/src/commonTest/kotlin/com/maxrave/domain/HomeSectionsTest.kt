package com.maxrave.domain

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.appendHomeSections
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionsTest {
    private fun shelf(title: String, id: String, thumbnail: String = "cover") = HomeItem(
        title = title,
        contents = listOf(Content(null, null, null, null, null, null,
            listOf(Thumbnail(1, thumbnail, 1)), id, id, null)),
    )

    @Test fun repeatedPagesDoNotRepeatQuickPicksOrPlaylists() {
        val first = listOf(shelf("Quick picks", "song"), shelf("Take it easy", "playlist"))
        assertEquals(first, first.appendHomeSections(first).appendHomeSections(first))
    }

    @Test fun sameTitleWithDifferentRecommendationsIsPreservedInOrder() {
        val first = shelf("Mixed for you", "one")
        val second = shelf("Mixed for you", "two")
        assertEquals(listOf(first, second), listOf(first).appendHomeSections(listOf(second)))
    }

    @Test fun refreshedArtworkDoesNotDuplicateTheSameRecommendation() {
        val original = shelf("Quick picks", "song", "old-cover")
        assertEquals(listOf(original), listOf(original).appendHomeSections(listOf(shelf("Quick picks", "song", "new-cover"))))
    }
}
