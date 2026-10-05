package com.maxrave.media3.exoplayer

import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

/** Audio and video URLs have separate keys; failures can evict both before a retry. */
internal class StreamUrlCache(
    private val maxEntries: Int = 128,
    private val currentTime: () -> LocalDateTime = ::now,
) {
    private data class Entry(val url: String, val expiresAt: LocalDateTime)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    init {
        require(maxEntries > 0)
    }

    @Synchronized
    operator fun get(mediaId: String): String? {
        val entry = entries[mediaId] ?: return null
        if (entry.expiresAt <= currentTime()) {
            entries.remove(mediaId)
            return null
        }
        return entry.url
    }

    @Synchronized
    fun put(mediaId: String, url: String, expiresAt: LocalDateTime) {
        val time = currentTime()
        entries.entries.removeIf { it.value.expiresAt <= time }
        if (expiresAt <= time) return
        entries[mediaId] = Entry(url, expiresAt)
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
    }

    @Synchronized
    fun invalidate(mediaId: String) {
        entries.remove(mediaId)
    }
}
