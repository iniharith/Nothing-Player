package com.nothingplayer.app

import java.io.File
import java.nio.file.Files

internal data class DesktopTrayIcons(val png: File, val ico: File) {
    val pngUri: String get() = png.toURI().toASCIIString()
    val icoUri: String get() = ico.toURI().toASCIIString()

    companion object {
        fun extract(): DesktopTrayIcons {
            fun extractIcon(extension: String): File {
                val file = Files.createTempFile("nothing-player-tray-", ".$extension").toFile()
                file.deleteOnExit()
                checkNotNull(DesktopTrayIcons::class.java.getResourceAsStream("/tray-icon.$extension")) {
                    "Bundled tray icon is missing"
                }.use { input -> file.outputStream().use(input::copyTo) }
                return file
            }
            return DesktopTrayIcons(extractIcon("png"), extractIcon("ico"))
        }
    }
}
