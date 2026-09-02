package com.vizuzik.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vizuzik.app.domain.model.LibraryScanState
import com.vizuzik.app.domain.repository.MusicRepository
import com.vizuzik.app.theme.ThemeController
import com.vizuzik.app.theme.ThemeId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themeController: ThemeController,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    val themeId: StateFlow<ThemeId> = themeController.themeId
    val scanState: StateFlow<LibraryScanState> = musicRepository.scanState

    fun setTheme(id: ThemeId) = themeController.setTheme(id)

    fun refreshLibrary() {
        viewModelScope.launch { musicRepository.refreshLibrary(force = true) }
    }
}
