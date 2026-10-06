package com.maxrave.domain.utils

object UpdateApkPolicy {
    fun selectApk(tag: String, assets: List<Pair<String?, String?>>): String? {
        val candidates = assets.filter { (name, url) ->
            name != null && url?.startsWith("https://github.com/iniharith/Nothing-Player/releases/download/") == true &&
                name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
        }
        return candidates.firstOrNull { it.first == "Nothing-Player-${tag.removePrefix("v")}-release.apk" }?.second
            ?: candidates.firstOrNull { it.first?.contains("universal", ignoreCase = true) == true }?.second
    }
}
