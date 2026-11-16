package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.pages.RelatedPage
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomePlaylistEndpointTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun card(playKey: String, menuKey: String?, menu: Boolean = true): MusicTwoRowItemRenderer {
        val menuJson = if (menu) """,
          "menu":{"menuRenderer":{"items":[
            {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"Shuffle play"}]},"icon":{"iconType":"MUSIC_SHUFFLE"},"navigationEndpoint":{"$menuKey":{"playlistId":"PLhome","params":"shuffle"}}}},
            {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"Start mix"}]},"icon":{"iconType":"MIX"},"navigationEndpoint":{"$menuKey":{"playlistId":"RDhome","params":"radio"}}}}
          ]}}
        """ else ""
        return json.decodeFromString("""{
          "title":{"runs":[{"text":"Your mix"}]},
          "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLhome","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}},
          "aspectRatio":"MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE",
          "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.test/mix.jpg","width":544,"height":544}]}}},
          "thumbnailOverlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"$playKey":{"playlistId":"PLhome","params":"play"}}}}}}
          $menuJson
        }""")
    }

    @Test fun retainsClassicPlaylistEndpoints() = checkEndpoints("watchPlaylistEndpoint", "watchPlaylistEndpoint")

    @Test fun retainsCurrentWatchEndpoints() = checkEndpoints("watchEndpoint", "watchEndpoint")

    @Test fun retainsMixedEndpointFormats() = checkEndpoints("watchEndpoint", "watchPlaylistEndpoint")

    private fun checkEndpoints(playKey: String, menuKey: String) {
        val item = assertIs<PlaylistItem>(RelatedPage.fromMusicTwoRowItemRenderer(card(playKey, menuKey)))
        assertEquals("PLhome", item.id)
        assertEquals("Your mix", item.title)
        assertEquals("play", item.playEndpoint?.params)
        assertEquals("shuffle", item.shuffleEndpoint.params)
        assertEquals("RDhome", item.radioEndpoint?.playlistId)
        assertEquals("radio", item.radioEndpoint?.params)
    }

    @Test fun mixWithoutMenuFallsBackToCurrentPlayEndpoint() {
        val item = assertIs<PlaylistItem>(RelatedPage.fromMusicTwoRowItemRenderer(card("watchEndpoint", null, false)))
        assertEquals("PLhome", item.shuffleEndpoint.playlistId)
        assertEquals("play", item.shuffleEndpoint.params)
        assertEquals("PLhome", item.radioEndpoint?.playlistId)
    }
}
