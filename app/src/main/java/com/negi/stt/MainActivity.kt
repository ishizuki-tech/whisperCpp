package com.negi.stt

import android.Manifest
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * MainActivity
 *
 * Hosts the Compose UI and provides the MainScreenViewModel to the UI tree.
 *
 * Notes:
 *  - Keep UI work on the Compose side and avoid performing blocking IO on the
 *    Activity's lifecycle callbacks.
 */
class MainActivity : ComponentActivity() {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge content (fits system windows off). This is optional and
        // depends on your theme/statusBar handling.
        enableEdgeToEdge()

        setContent {
            // Obtain Application for ViewModel factory
            val app = application as Application

            // Acquire ViewModel via factory so it receives Application instance.
            // We resolve it inside composition so it is tied to Compose lifecycle.
            val viewModel: MainScreenViewModel =
                viewModel(factory = MainScreenViewModel.factory(app))

            // PermissionWrapper will request runtime permissions if necessary,
            // and only when granted will it show the main screen content.
            PermissionWrapper {
                MainScreenEntryPoint(viewModel)
            }
        }
    }
}
