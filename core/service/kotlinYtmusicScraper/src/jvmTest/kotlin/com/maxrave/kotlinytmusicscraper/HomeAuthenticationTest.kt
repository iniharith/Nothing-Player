package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.ResponseContext
import com.maxrave.kotlinytmusicscraper.utils.parseCookieString
import com.maxrave.kotlinytmusicscraper.utils.youtubeAuthorization
import kotlinx.serialization.json.Json
import kotlin.test.*

class HomeAuthenticationTest {
    @Test fun cookieSeparatorsDoNotRequireSpacesAndValuesKeepPadding() {
        val cookies = parseCookieString("SID=account;SAPISID=signing==; __Secure-3PAPISID=other; invalid; =broken;")
        assertEquals("signing==", cookies["SAPISID"])
        assertEquals("other", cookies["__Secure-3PAPISID"])
        assertEquals(3, cookies.size)
    }

    @Test fun authorizationUsesTheCookiePassedToThisRequest() {
        val origin = "https://music.youtube.com"
        val accountA = youtubeAuthorization("SAPISID=account-a", origin, 123)
        val accountB = youtubeAuthorization("SAPISID=account-b", origin, 123)
        assertNotNull(accountA)
        assertNotNull(accountB)
        assertNotEquals(accountA, accountB)
        assertNotEquals(accountA, youtubeAuthorization("SAPISID=account-a", "https://www.youtube.com", 123))
        assertEquals(accountA, youtubeAuthorization("__Secure-3PAPISID=account-a", origin, 123))
        assertEquals(accountA, youtubeAuthorization("__Secure-1PAPISID=account-a", origin, 123))
    }

    @Test fun missingSigningCookieDoesNotGenerateAuthorizationForNull() {
        assertNull(youtubeAuthorization("SID=account", "https://music.youtube.com", 123))
        assertNull(youtubeAuthorization(null, "https://music.youtube.com", 123))
        assertNull(youtubeAuthorization("SAPISID=", "https://music.youtube.com", 123))
    }

    @Test fun responsePreservesServerSignedOutStatus() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val context = json.decodeFromString<ResponseContext>("""{"mainAppWebResponseContext":{"loggedOut":true}}""")
        assertEquals(true, context.mainAppWebResponseContext?.loggedOut)
        assertTrue(context.isLoggedOut)
        val guest = json.decodeFromString<ResponseContext>("""{"serviceTrackingParams":[{"service":"GFEEDBACK","params":[{"key":"logged_in","value":"0"}]}]}""")
        assertTrue(guest.isLoggedOut)
        val account = json.decodeFromString<ResponseContext>("""{"serviceTrackingParams":[{"service":"GFEEDBACK","params":[{"key":"logged_in","value":"1"}]}]}""")
        assertFalse(account.isLoggedOut)
    }
}
