package com.maxrave.domain.data.model.home

/** Preserve server order, but do not append an identical shelf from a repeated page. */
fun List<HomeItem>.appendHomeSections(next: List<HomeItem>): List<HomeItem> =
    (this + next).distinctBy { shelf ->
        listOf(shelf.title, shelf.subtitle, shelf.channelId) to shelf.contents.map { item ->
            item?.let { listOf(it.playlistId, it.videoId, it.browseId, it.title) }
        }
    }
