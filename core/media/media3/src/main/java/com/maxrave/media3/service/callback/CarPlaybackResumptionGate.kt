package com.maxrave.media3.service.callback

/** Shares one automatic resume across the car host's browser and playback controllers. */
internal class CarPlaybackResumptionGate<T> {
    private val controllers = mutableSetOf<T>()
    private var pausedByUser = false

    fun connect(controller: T): Boolean {
        val wasDisconnected = controllers.isEmpty()
        if (!controllers.add(controller)) return false
        if (wasDisconnected) pausedByUser = false
        return wasDisconnected
    }

    fun disconnect(controller: T): Boolean {
        controllers.remove(controller)
        return controllers.isEmpty()
    }

    fun isConnected(controller: T): Boolean = controller in controllers

    fun onUserPause() {
        if (controllers.isNotEmpty()) pausedByUser = true
    }

    fun canAutomaticallyResume(): Boolean = controllers.isNotEmpty() && !pausedByUser

    fun reset() {
        controllers.clear()
        pausedByUser = false
    }
}

internal const val CAR_BROWSER_CONNECTION_HINT = "com.nothingplayer.AUTO_CONNECTION"
