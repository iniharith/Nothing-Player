package com.maxrave.kotlinytmusicscraper

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountHomeVideosTest {
    @Test fun parsesClassicAndCurrentHomeCardsAndDeduplicates() {
        val json = """{"contents":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"My recommendation"}]},"ownerText":{"runs":[{"text":"My channel"}]},"lengthText":{"simpleText":"3:12"},"thumbnail":{"thumbnails":[{"url":"https://example.test/image"}]}}},{"lockupViewModel":{"contentId":"12345678901","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","metadata":{"lockupMetadataViewModel":{"title":{"content":"Personal home"}}},"contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://example.test/new"}]}}}}},{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"My recommendation"}}}]}"""
        val videos = parseAccountHomeVideos(Json.parseToJsonElement(json))
        assertEquals(listOf("abcdefghijk", "12345678901"), videos.map { it.id })
        assertEquals("Personal home", videos[1].title)
        assertEquals("https://example.test/new", videos[1].thumbnail)
    }
    @Test fun preservesFeedContinuationAndParsesAppendedCards() {
        val json = """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Next recommendation"}}},{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"next-account-page"}}}}]}}]}"""
        val page = parseAccountHomeVideoPage(Json.parseToJsonElement(json))
        assertEquals(listOf("abcdefghijk"), page.videos.map { it.id })
        assertEquals("next-account-page", page.continuation)
    }

    @Test fun ignoresUnrelatedTokensAndAllowsEndOfFeed() {
        val json = """{"menu":{"continuationCommand":{"token":"not-a-feed-page"}},"contents":[]}"""
        val page = parseAccountHomeVideoPage(Json.parseToJsonElement(json))
        assertEquals(emptyList(), page.videos)
        assertEquals(null, page.continuation)
    }
}
