package com.maxrave.domain.data.model.update

data class UpdateData(
    val apkUrl: String? = null,
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    val releaseUrl: String = "https://github.com/iniharith/Nothing-Player/releases/latest",
)
