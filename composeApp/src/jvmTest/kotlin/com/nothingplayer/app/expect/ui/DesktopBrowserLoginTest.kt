package com.nothingplayer.app.expect.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.*

class DesktopBrowserLoginTest {
    @Test fun sessionImportWaitsUntilTheUserClosesTheSignInWindow() {
        val running = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() {}
            override fun isAlive() = true
        }
        val constructor = DesktopBrowserLogin::class.java.getDeclaredConstructor(Path::class.java, Process::class.java, File::class.java)
        constructor.isAccessible = true
        val login = constructor.newInstance(Path.of("app-owned-profile"), running, File("unused-browser"))
        assertFailsWith<DesktopSignInWindowStillOpen> { login.readCookies() }
    }

    @Test fun signInUsesANormalBrowserWithoutDebuggingOrAutomation() {
        val arguments = desktopBrowserArguments(Path.of("C:/app-owned-profile"), importing = false)
        assertEquals("https://music.youtube.com/", arguments.last())
        assertTrue(arguments.any { it.startsWith("--user-data-dir=") })
        assertFalse(arguments.any { "remote-debugging" in it || "headless" in it || "automation" in it })
    }

    @Test fun sessionImportIsLocalAndDoesNotNavigateToGoogleLogin() {
        val arguments = desktopBrowserArguments(Path.of("C:/app-owned-profile"), importing = true)
        assertEquals("about:blank", arguments.last())
        assertTrue("--remote-debugging-address=127.0.0.1" in arguments)
        assertTrue(arguments.any { it.startsWith("--user-data-dir=") })
        assertFalse(arguments.any { it.startsWith("https:") })
    }

    @Test fun importsOnlyYouTubeSessionAndPreservesCookieValues() {
        val response = Json.parseToJsonElement("""{"cookies":[
            {"domain":".youtube.com","name":"SAPISID","value":"test==","path":"/","secure":true,"expires":9999999999},
            {"domain":".google.com","name":"SID","value":"not-imported","path":"/"},
            {"domain":"notyoutube.com","name":"SID","value":"not-imported","path":"/"},
            {"domain":"www.youtube.com","name":"WEB_ONLY","value":"video","path":"/"},
            {"domain":"music.youtube.com","name":"PREF","value":"music","path":"/"},
            {"domain":".youtube.com","name":"OLD","value":"expired","expires":1}
        ]}""").jsonObject
        val result = decodeYouTubeLoginCookies(response)
        assertTrue("SAPISID=test==" in result.header)
        assertTrue("PREF=music" in result.header)
        assertFalse("WEB_ONLY" in result.header)
        assertFalse("not-imported" in result.netscape)
        assertFalse("expired" in result.netscape)
        assertTrue(".youtube.com\tTRUE\t/\tTRUE\t9999999999\tSAPISID\ttest==" in result.netscape)
        assertEquals("DesktopLoginCookies(private session)", result.toString())
    }

    @Test fun anonymousBrowserCannotBeImportedAsSignedIn() {
        val response = Json.parseToJsonElement("""{"cookies":[{"domain":".youtube.com","name":"PREF","value":"guest"}]}""").jsonObject
        assertFailsWith<IllegalStateException> { decodeYouTubeLoginCookies(response) }
    }

    @Test fun rejectsHeaderInjectionAndPrefersMusicSpecificCookie() {
        val response = Json.parseToJsonElement("""{"cookies":[
            {"domain":".youtube.com","name":"SAPISID","value":"broad"},
            {"domain":"music.youtube.com","name":"SAPISID","value":"selected"},
            {"domain":".youtube.com","name":"BAD","value":"unsafe\r\nInjected:value"}
        ]}""").jsonObject
        val result = decodeYouTubeLoginCookies(response)
        assertEquals("SAPISID=selected", result.header)
        assertFalse("Injected" in result.netscape)
    }
}
