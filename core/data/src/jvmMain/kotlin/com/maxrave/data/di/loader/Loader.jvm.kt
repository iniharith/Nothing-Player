package com.maxrave.data.di.loader

import com.nothingplayer.media_jvm.di.loadDesktopPlayerModule

actual fun loadMediaService() {
    loadDesktopPlayerModule()
}
