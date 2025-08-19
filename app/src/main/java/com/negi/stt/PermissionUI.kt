package com.negi.stt

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * PermissionWrapper composable that ensures required permissions are available before
 * showing the provided content.
 *
 * Behavior:
 *  - Queries MainScreenViewModel for the list of required permissions.
 *  - Requests missing permissions using the platform permission launcher.
 *  - If the user denies with "Don't ask again" (permanently denied), shows a dialog
 *    that links to the app settings.
 *  - If the user denies but rationale can be shown, shows a rationale dialog with an
 *    option to request again.
 *
 * Notes:
 *  - UI texts are in English per request.
 *  - Comments and KDoc are in English as requested.
 */
@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: MainScreenViewModel = viewModel(factory = MainScreenViewModel.factory(app))

    // Dialog control states
    var showRationaleDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Coroutine scope for launching suspend calls from UI event handlers
    val uiScope = rememberCoroutineScope()

    // Helper to collect currently missing permissions snapshot
    suspend fun getMissingPermissions(): List<String> {
        return viewModel.getRequiredPermissions().filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    // Permission launcher using Activity Result API.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.updatePermissionsStatus()

        val denied = result.filterValues { granted -> !granted }.keys.toList()
        if (denied.isEmpty()) {
            showRationaleDialog = false
            showSettingsDialog = false
            return@rememberLauncherForActivityResult
        }

        val activity = context as? Activity
        if (activity != null) {
            // If any denied permission returns false for shouldShowRequestPermissionRationale,
            // treat that as a "go to settings" case (user possibly selected "Don't ask again").
            val anyPermanentDenied = denied.any { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            if (anyPermanentDenied) {
                showSettingsDialog = true
                showRationaleDialog = false
                return@rememberLauncherForActivityResult
            }
        }

        // Otherwise show rationale (user denied but we can ask again).
        showRationaleDialog = true
        showSettingsDialog = false
    }

    // Initial check and request on first composition.
    LaunchedEffect(Unit) {
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        } else {
            viewModel.updatePermissionsStatus()
        }
    }

    // If all required permissions are present, show main content.
    if (viewModel.hasAllRequiredPermissions) {
        content()
        return
    }

    // Rationale dialog (user can be re-prompted)
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    uiScope.launch {
                        val missingNow = getMissingPermissions()
                        if (missingNow.isNotEmpty()) launcher.launch(missingNow.toTypedArray())
                    }
                    showRationaleDialog = false
                }) { Text("Request again") }
            },
            dismissButton = {
                TextButton(onClick = { showRationaleDialog = false }) { Text("Close") }
            },
            title = { Text("Microphone permission required") },
            text = { Text("This app needs access to your microphone to record audio. Please grant the permission.") }
        )
        return
    }

    // Settings dialog (permanently denied / "Don't ask again")
    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    showSettingsDialog = false
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Close") }
            },
            title = { Text("Microphone permission required") },
            text = { Text("Microphone permission has been permanently denied. Please enable it in app settings.") }
        )
        return
    }

    // Fallback UI while waiting for permission decisions
    Column {
        Text("Microphone permission is required.")
        Text("The app is requesting permission to access the microphone.")
    }
}
/*
変更点（日本語で簡潔に）
ダイアログ／ボタン／説明文など ユーザー向け文字列をすべて英語 に変更しました。
onClick 内で LaunchedEffect を使っていた箇所を rememberCoroutineScope() + uiScope.launch { ... } に修正して、Compose のルールに沿った安全なサスペンド呼び出しを行うようにしました。
以前のロジック（rationale と permanent-deny を分けて表示する判断や設定画面起動のフラグ付け）は保持しています。
必要であれば：
ダイアログ文言を strings.xml に移して多言語対応にする実装（英語／日本語切替）を作ります。
設定→戻ったときに自動で再チェックする処理（Activity result を受けて再確認）を追加します。
 */