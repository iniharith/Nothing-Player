package com.maxrave.media3.exoplayer

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Java Listener defaults are not forwarded by Kotlin interface delegation. */
internal fun forwardingListener(
    listener: Player.Listener,
    playbackIntent: (() -> Boolean)? = null,
    playbackState: (() -> Int)? = null,
    suppressionReason: (() -> Int)? = null,
    metadata: ((MediaMetadata) -> MediaMetadata)? = null,
    onEvents: (Player.Events) -> Unit,
): Player.Listener = Proxy.newProxyInstance(Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java)) { proxy, method, args ->
    if (method.declaringClass == Any::class.java) {
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Playback listener bridge"
            else -> null
        }
    } else if (args?.getOrNull(1) is Player.Events) {
        onEvents(args[1] as Player.Events)
        null
    } else {
        val values = args?.toMutableList()?.toTypedArray() ?: emptyArray()
        when (method.name) {
            "onPlayWhenReadyChanged" -> playbackIntent?.let { values[0] = it() }
            "onPlaybackStateChanged" -> playbackState?.let { values[0] = it() }
            "onPlaybackSuppressionReasonChanged" -> suppressionReason?.let { values[0] = it() }
            "onMediaMetadataChanged" -> metadata?.let { values[0] = it(values[0] as MediaMetadata) }
        }
        try { method.invoke(listener, *values) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
} as Player.Listener
