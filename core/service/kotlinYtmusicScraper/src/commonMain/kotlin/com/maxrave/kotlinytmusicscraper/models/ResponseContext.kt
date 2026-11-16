package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class ResponseContext(
    val visitorData: String? = null,
    val serviceTrackingParams: List<ServiceTrackingParam>?,
    val mainAppWebResponseContext: MainAppWebResponseContext? = null,
    val webResponseContextExtensionData: WebResponseContextExtensionData? = null,
) {
    /** Music browse responses report sign-in state through service tracking on some clients. */
    val isLoggedOut: Boolean
        get() = mainAppWebResponseContext?.loggedOut == true ||
            serviceTrackingParams.orEmpty().any { service ->
                service.params.any { it.key == "logged_in" && it.value == "0" }
            }

    @Serializable
    data class WebResponseContextExtensionData(
        val ytConfigData: YtConfigData? = null,
    ) {
        @Serializable
        data class YtConfigData(
            val visitorData: String? = null,
        )
    }
    @Serializable
    data class MainAppWebResponseContext(
        val datasyncId: String? = null,
        val loggedOut: Boolean? = null,
    )
    @Serializable
    data class ServiceTrackingParam(
        val params: List<Param>,
        val service: String,
    ) {
        @Serializable
        data class Param(
            val key: String,
            val value: String,
        )
    }
}
