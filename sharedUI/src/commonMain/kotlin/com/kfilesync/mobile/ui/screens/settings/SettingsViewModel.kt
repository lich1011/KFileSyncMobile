package com.kfilesync.mobile.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kfilesync.mobile.application.service.SettingsAppService
import com.kfilesync.mobile.application.service.SettingsSnapshot
import com.kfilesync.mobile.domain.service.SyncPolicyId
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Settings tab (T5.3).
 *
 * [snapshot] mirrors [SettingsSnapshot] but adds a couple of UI-only flags:
 * - [aliasDraft]: the user's in-progress edit (the persisted alias only
 * updates once they tap "Save"). Keeping it separate prevents partial
 * edits from blowing away the persisted name on screen rotation.
 * - [isClearing]: drives a small spinner on the "Clear cache" button.
 * - [errorMessage]: transient banner; cleared after the next successful op.
 *
 * Marked [Immutable] (Phase 6 T6.2) - nothing in here is mutable in place;
 * the Compose stability inference can short-circuit equality checks.
 */
@androidx.compose.runtime.Immutable
data class SettingsUiState(
    val snapshot: SettingsSnapshot,
    val aliasDraft: String,
    val isClearing: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Settings tab ViewModel (T5.3).
 *
 * Hot collector: [observeSettings] is a StateFlow under the hood, so we
 * subscribe once in `init` and let Compose re-render on every emission.
 * Writes (alias, policy, clearCache) go through [SettingsAppService] which
 * persists + re-emits - we don't need to update `_state` ourselves except
 * for the UI-only flags ([aliasDraft], [isClearing], [errorMessage]).
 *
 * Constructor-injected via Koin (`viewModelOf(::SettingsViewModel)`); the
 * single dependency is the AppService - keep this small.
 */
class SettingsViewModel(
    private val settingsService: SettingsAppService
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            snapshot = SettingsSnapshot.EMPTY,
            aliasDraft = ""
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsService.observeSettings().collect { snap ->
                _state.update {
                    // Don't clobber the user's in-progress edit if they
                    // haven't saved yet (aliasDraft == previous snap.alias
                    // means they haven't touched it).
                    val draftIsCurrent = it.aliasDraft == it.snapshot.alias
                    val newDraft = if (draftIsCurrent) snap.alias else it.aliasDraft
                    it.copy(snapshot = snap, aliasDraft = newDraft)
                }
            }
        }

        viewModelScope.launch {
            runCatching { settingsService.refresh() }
                .onFailure { Napier.w("SettingsViewModel: initial refresh failed", it) }
        }
    }

    /** Edit-as-you-type alias buffer. Persisted only on [saveAlias]. */
    fun onAliasChanged(draft: String) {
        _state.update { it.copy(aliasDraft = draft, errorMessage = null) }
    }

    fun saveAlias() {
        val draft = _state.value.aliasDraft.trim()
        if (draft.isBlank()) {
            _state.update { it.copy(errorMessage = "Alias cannot be empty") }
            return
        }
        viewModelScope.launch {
            settingsService.setAlias(draft).onFailure { t ->
                _state.update { it.copy(errorMessage = t.message ?: "Failed to save alias") }
            }
        }
    }

    fun selectPolicy(id: SyncPolicyId) {
        viewModelScope.launch {
            settingsService.setSyncPolicy(id).onFailure { t ->
                _state.update { it.copy(errorMessage = t.message ?: "Failed to update policy") }
            }
        }
    }

    fun clearCache() {
        _state.update { it.copy(isClearing = true, errorMessage = null) }
        viewModelScope.launch {
            settingsService.clearCache()
                .onSuccess { _state.update { it.copy(isClearing = false) } }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            isClearing = false,
                            errorMessage = t.message ?: "Cache clear failed"
                        )
                    }
                }
        }
    }

    /** Dismiss the inline error banner. */
    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}