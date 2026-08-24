package com.nothingplayer.app.expect

enum class Orientation {
    PORTRAIT, LANDSCAPE, UNSPECIFIED
}

expect fun currentOrientation(): Orientation