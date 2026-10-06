package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.extractor.RegularVideo
import kotlinx.serialization.json.*

internal fun parseAccountHomeVideos(root: JsonElement): List<RegularVideo> {
    val result = linkedMapOf<String, RegularVideo>()
    fun JsonElement?.text(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonObject -> this["simpleText"].text().ifBlank { this["content"].text() }.ifBlank {
            (this["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.get("text").text() }.orEmpty()
        }
        else -> ""
    }
    fun find(node: JsonElement?, key: String): JsonElement? = when (node) {
        is JsonObject -> node[key] ?: node.values.firstNotNullOfOrNull { find(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { find(it, key) }
        else -> null
    }
    fun visit(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val video = node["videoRenderer"] as? JsonObject
                val lockup = node["lockupViewModel"] as? JsonObject
                val item = video ?: lockup
                if (item != null && (video != null || item["contentType"].text() == "LOCKUP_CONTENT_TYPE_VIDEO")) {
                    val id = (item["videoId"] ?: item["contentId"]).text()
                    val metadata = (item["metadata"] as? JsonObject)?.get("lockupMetadataViewModel") as? JsonObject
                    val title = (video?.get("title") ?: metadata?.get("title")).text()
                    val owner = (video?.get("ownerText") ?: video?.get("shortBylineText") ?: find(metadata?.get("metadata"), "text")).text()
                    val images = (find(item["thumbnail"], "thumbnails") ?: find(item["contentImage"], "sources")) as? JsonArray
                    val thumbnail = (images?.lastOrNull() as? JsonObject)?.get("url").text()
                    val duration = (video?.get("lengthText") ?: find(item["contentImage"], "text")).text()
                    val seconds = duration.split(':').fold(0) { total, part -> total * 60 + (part.toIntOrNull() ?: 0) }
                    if (id.matches(Regex("[A-Za-z0-9_-]{11}")) && title.isNotBlank()) {
                        result[id] = RegularVideo(id, title, owner, seconds, thumbnail.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" })
                    }
                } else node.values.forEach(::visit)
            }
            is JsonArray -> node.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return result.values.toList()
}
