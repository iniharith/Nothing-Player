package com.maxrave.media3.exoplayer

/** Match precaching to the same order used by transport commands. */
internal fun upcomingTrackIndices(
    queueOrder: List<Int>,
    currentIndex: Int,
    count: Int,
    repeatAll: Boolean,
    repeatOne: Boolean,
): List<Int> {
    val currentPosition = queueOrder.indexOf(currentIndex)
    if (currentPosition < 0 || count <= 0 || repeatOne) return emptyList()
    return buildList {
        for (offset in 1..minOf(count, queueOrder.size - 1)) {
            val position = currentPosition + offset
            if (!repeatAll && position >= queueOrder.size) break
            add(queueOrder[position % queueOrder.size])
        }
    }
}
