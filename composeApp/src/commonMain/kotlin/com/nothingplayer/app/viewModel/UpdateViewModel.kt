package com.nothingplayer.app.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.UpdateRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.UpdatePolicy
import com.maxrave.logger.Logger
import com.nothingplayer.app.utils.VersionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class UpdateState(
    val isChecking: Boolean = false,
    val availableUpdate: UpdateData? = null,
    val hasUpdate: Boolean = false,
    val checkedManually: Boolean = false,
    val error: Boolean = false,
)

/** Checks only when the app enters the foreground; no worker or background polling. */
class UpdateViewModel(
    private val dataStoreManager: DataStoreManager,
    private val updateRepository: UpdateRepository,
) : ViewModel() {
    val autoCheckForUpdates = dataStoreManager.autoCheckForUpdates
        .map { it == DataStoreManager.TRUE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()

    private var checkJob: Job? = null
    private var manualCheckInProgress = false
    private var lastFailedCheckTime = 0L

    @OptIn(ExperimentalTime::class)
    fun checkForUpdates(manual: Boolean = false) {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            val now = Clock.System.now().toEpochMilliseconds()
            if (!manual) {
                if (dataStoreManager.autoCheckForUpdates.first() != DataStoreManager.TRUE) return@launch
                if (!UpdatePolicy.isCheckDue(now, dataStoreManager.lastUpdateCheckTime.first())) return@launch
                // Offline launches may retry on the next foreground visit without hammering GitHub.
                if (lastFailedCheckTime != 0L && now >= lastFailedCheckTime && now - lastFailedCheckTime < 15 * 60 * 1000L) {
                    return@launch
                }
            }
            manualCheckInProgress = manual
            _state.value = UpdateState(isChecking = true, checkedManually = manual)
            try {
                when (val result = updateRepository.checkForGithubReleaseUpdate().first()) {
                    is Resource.Success -> {
                        val release = requireNotNull(result.data)
                        dataStoreManager.setLastUpdateCheckTime(now)
                        lastFailedCheckTime = 0L
                        val hasUpdate = UpdatePolicy.isNewerRelease(release.tagName, VersionManager.getVersionName())
                        _state.value = UpdateState(
                            availableUpdate = release.takeIf { hasUpdate },
                            hasUpdate = hasUpdate,
                            checkedManually = manual,
                        )
                    }
                    is Resource.Error -> {
                        lastFailedCheckTime = now
                        Logger.w("UpdateViewModel", "GitHub update check failed: ${result.message}")
                        _state.value = UpdateState(checkedManually = manual, error = true)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                lastFailedCheckTime = now
                Logger.w("UpdateViewModel", "GitHub update check failed: ${exception.message}")
                _state.value = UpdateState(checkedManually = manual, error = true)
            } finally {
                _state.value = _state.value.copy(isChecking = false)
                manualCheckInProgress = false
            }
        }
    }

    fun cancelAutomaticCheck() {
        if (!manualCheckInProgress) checkJob?.cancel()
    }

    fun setAutoCheckForUpdates(enabled: Boolean) {
        viewModelScope.launch {
            dataStoreManager.setAutoCheckForUpdates(enabled)
            if (enabled) checkForUpdates() else cancelAutomaticCheck()
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(availableUpdate = null)
    }
}
