package com.maxrave.domain.data.model.update

data class UpdateData(
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    val releaseUrl: String = "https://github.com/iniharith/Nothing-Player/releases/latest",
)
