package com.maxrave.data.repository

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.repository.UpdateRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class UpdateRepositoryImpl(
    private val youTube: YouTube,
) : UpdateRepository {
    override fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>> =
        flow {
            youTube
                .checkForGithubReleaseUpdate()
                .onSuccess { response ->
                    val tag = response.tagName?.takeIf { it.isNotBlank() }
                    if (tag == null || response.draft == true || response.prerelease == true) {
                        emit(Resource.Error<UpdateData>("GitHub did not return a stable release"))
                        return@onSuccess
                    }
                    emit(
                        Resource.Success(
                            UpdateData(
                                apkUrl = com.maxrave.domain.utils.UpdateApkPolicy.selectApk(
                                    tag, response.assets.orEmpty().filterNotNull().map { it.name to it.browserDownloadUrl },
                                ),
                                tagName = tag,
                                releaseTime = response.publishedAt ?: "",
                                body = response.body ?: "",
                                releaseUrl = response.htmlUrl
                                    ?.takeIf { it.startsWith("https://github.com/iniharith/Nothing-Player/releases/") }
                                    ?: "https://github.com/iniharith/Nothing-Player/releases/latest",
                            ),
                        ),
                    )
                }.onFailure {
                    if (it is CancellationException) throw it
                    emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun checkForFdroidUpdate(): Flow<Resource<UpdateData>> =
        flow {
            youTube
                .checkForFdroidUpdate()
                .onSuccess { response ->
                    val latestVersion = response.packages.maxBy { it.versionCode }
                    emit(
                        Resource.Success(
                            UpdateData(
                                tagName = latestVersion.versionName,
                                releaseTime = null,
                                body =
                                    $$"""
                                    ### Update via F-Droid, changelogs: 
                                    - https://github.com/iniharith/Nothing-Player/blob/dev/fastlane/metadata/android/en-US/changelogs/$${latestVersion.versionCode}.txt
                                    """.trimIndent(),
                            ),
                        ),
                    )
                }.onFailure {
                    emit(Resource.Error<UpdateData>(it.localizedMessage ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)
}
