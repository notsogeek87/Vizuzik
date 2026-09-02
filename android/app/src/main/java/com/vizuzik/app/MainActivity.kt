package com.vizuzik.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vizuzik.app.theme.ThemeController
import com.vizuzik.app.theme.ThemeId
import com.vizuzik.app.ui.VizuzikApp
import com.vizuzik.app.theme.VizuzikTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeId by viewModel.themeId.collectAsStateWithLifecycle()
            VizuzikTheme(themeId = themeId) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VizuzikApp()
                }
            }
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(themeController: ThemeController) : ViewModel() {
    val themeId: StateFlow<ThemeId> = themeController.themeId
}
