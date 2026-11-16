package com.nothingplayer.app

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DesktopTrayIconsTest {
    // Exercise the library's actual binary entry point, which is Kotlin-internal.
    private fun extractToTempIfDifferent(uri: String): File = requireNotNull(
        Class.forName("com.kdroid.composetray.utils.JarResourceExtractorKt")
            .getMethod("extractToTempIfDifferent", String::class.java)
            .invoke(null, uri) as? File,
    )

    @Test fun bundledIconsAreAcceptedByTheActualTrayLibrary() {
        val icons = DesktopTrayIcons.extract()
        for ((file, uri) in listOf(icons.png to icons.pngUri, icons.ico to icons.icoUri)) {
            assertEquals("file", URI(uri).scheme)
            val extracted = extractToTempIfDifferent(uri)
            assertContentEquals(file.readBytes(), extracted.readBytes())
        }
    }

    @Test fun pathsWithSpacesAreEncodedForTheTrayLibrary() {
        val directory = Files.createTempDirectory("Nothing Player tray ").toFile().apply { deleteOnExit() }
        val bundled = DesktopTrayIcons.extract()
        val png = directory.resolve("tray icon.png").apply { deleteOnExit(); writeBytes(bundled.png.readBytes()) }
        val ico = directory.resolve("tray icon.ico").apply { deleteOnExit(); writeBytes(bundled.ico.readBytes()) }
        val icons = DesktopTrayIcons(png, ico)
        assertContentEquals(png.readBytes(), extractToTempIfDifferent(icons.pngUri).readBytes())
        assertContentEquals(ico.readBytes(), extractToTempIfDifferent(icons.icoUri).readBytes())
    }
}
