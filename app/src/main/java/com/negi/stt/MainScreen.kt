@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.negi.stt

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * RequestPermissionsIfNeeded
 * --------------------------------------------------------
 * Request required runtime permissions if not granted.
 *
 * The composable reads the permission list from the ViewModel and launches
 * the platform permission dialog on first composition if needed.
 */
@Composable
private fun RequestPermissionsIfNeeded(viewModel: MainScreenViewModel) {
    val context = LocalContext.current
    val permissions = remember { viewModel.getRequiredPermissions().toTypedArray() }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.updatePermissionsStatus()
    }

    LaunchedEffect(Unit) {
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            launcher.launch(notGranted.toTypedArray())
        } else {
            viewModel.updatePermissionsStatus()
        }
    }
}

/* ------------------ MainScreen ------------------ */

/**
 * MainScreen composable
 *
 * - Renders top bar, recordings list, and a primary action button.
 * - All user-facing strings are in English.
 *
 * @param viewModel the screen ViewModel
 * @param canTranscribe whether transcription is available/enabled
 * @param isRecording whether the app is currently recording
 * @param selectedIndex selected record index (UI selection)
 * @param onSelect callback when a record is selected
 * @param onRecordTapped toggle recording callback
 * @param onCardClick callback to play a recording
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainScreenViewModel,
    canTranscribe: Boolean,
    isRecording: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onRecordTapped: () -> Unit,
    onCardClick: (String, Int) -> Unit,
    onCardDoubleTap: (Int) -> Unit // <-- new
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteIndex by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()

    // Ensure permissions are requested when MainScreen appears
    RequestPermissionsIfNeeded(viewModel)

    Scaffold(topBar = { TopBar(viewModel) }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main list area (flexible)
            RecordingList(
                records = viewModel.myRecords,
                listState = listState,
                selectedIndex = selectedIndex,
                canTranscribe = canTranscribe,
                onSelect = onSelect,
                onCardClick = onCardClick,
                onCardDoubleTap = onCardDoubleTap, // <-- pass through
                onDeleteRequest = {
                    pendingDeleteIndex = it
                    showDeleteDialog = true
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Primary action button (Record / Stop)
            StyledButton(
                text = if (isRecording) "Stop" else "Record",
                onClick = onRecordTapped,
                enabled = canTranscribe,
                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Confirm delete dialog
    if (showDeleteDialog && pendingDeleteIndex != -1) {
        ConfirmDeleteDialog(
            onConfirm = {
                viewModel.removeRecordAt(pendingDeleteIndex)
                showDeleteDialog = false
                pendingDeleteIndex = -1
            },
            onCancel = {
                showDeleteDialog = false
                pendingDeleteIndex = -1
            }
        )
    }
}

/* ------------------ Config Button & Dialog ------------------ */

/**
 * Config button that opens a dialog to change language/model/translate toggle.
 *
 * Note: UI strings are English. Model list and language list are example defaults.
 */
@Composable
fun ConfigButtonWithDialog(viewModel: MainScreenViewModel) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Language selection
                    Text("Select language")
                    val languages = listOf("en" to "English", "ja" to "Japanese", "sw" to "Swahili")
                    DropdownSelector(
                        currentValue = viewModel.selectedLanguage,
                        options = languages,
                        onSelect = { viewModel.updateSelectedLanguage(it) }
                    )

                    // Model selection
                    Spacer(Modifier.height(8.dp))
                    Text("Select model")
                    val models = listOf(
                        "ggml-tiny-q5_1.bin",
                        "ggml-tiny-q8_0.bin",
                        "ggml-base-q5_1.bin",
                        "ggml-base-q8_0.bin",
                        "ggml-small-q5_1.bin",
                        "ggml-small-q8_0.bin",
                        "ggml-medium-q5_0.bin",
                        "ggml-medium-q8_0.bin"
                    )
                    DropdownSelector(
                        currentValue = viewModel.selectedModel,
                        options = models.map { it to it },
                        onSelect = { viewModel.updateSelectedModel(it) }
                    )

                    // Translate toggle
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.translateToEnglish,
                            onCheckedChange = { viewModel.updateTranslate(it) }
                        )
                        Text("Translate to English")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * Simple dropdown selector used in the settings dialog.
 *
 * @param currentValue currently selected key
 * @param options list of pairs (key, label)
 * @param onSelect callback when selection changes
 */
@Composable
private fun DropdownSelector(
    currentValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedLabel by remember {
        mutableStateOf(options.firstOrNull { it.first == currentValue }?.second ?: "")
    }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selectedLabel)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        selectedLabel = label
                        expanded = false
                    }
                )
            }
        }
    }
}

/* ------------------ TopBar ------------------ */

/**
 * App top bar with app info and settings action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(viewModel: MainScreenViewModel) {
    var showAboutDialog by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFF2196F3))
                Text(
                    text = "Whisper App",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.Black
                )
                LanguageLabel(
                    languageCode = viewModel.selectedLanguage,
                    selectedModel = viewModel.selectedModel
                )
            }
        },
        actions = {
            IconButton(onClick = { showAboutDialog = true }) {
                Icon(Icons.Default.Info, contentDescription = "App Info")
            }
            ConfigButtonWithDialog(viewModel)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFFFFF176),
            titleContentColor = Color.Black,
            actionIconContentColor = Color.DarkGray
        )
    )

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

/* ------------------ AboutDialog ------------------ */

/**
 * Simple about dialog for the app.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About this app") },
        text = {
            Column {
                Text("Whisper App v0.0.1")
                Spacer(Modifier.height(8.dp))
                Text("This app demonstrates offline transcription using Whisper.cpp.")
                Spacer(Modifier.height(4.dp))
                Text("Supported languages: Japanese / English / Swahili")
                Spacer(Modifier.height(8.dp))
                Text("Developer: Shu Ishizuki (石附 支)")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/* ------------------ LanguageLabel ------------------ */

/**
 * Compact label showing selected language and model.
 */
@Composable
private fun LanguageLabel(
    languageCode: String,
    selectedModel: String
) {
    val label = mapOf(
        "ja" to "Japanese",
        "en" to "English",
        "sw" to "Swahili"
    )[languageCode] ?: "Unknown"

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Language: $label", style = MaterialTheme.typography.labelSmall)
            Text("Model: $selectedModel", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/* ------------------ RecordingList ------------------ */

/**
 * Recording list with swipe-to-delete and selection behavior.
 *
 * NOTE:
 * - This function assumes existence of project-provided "SwipeToDismissBox"
 *   and "rememberSwipeToDismissBoxState" APIs — keep them or replace with
 *   another swipe-to-dismiss implementation if necessary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingList(
    records: List<myRecord>,
    listState: LazyListState,
    selectedIndex: Int,
    canTranscribe: Boolean,
    onSelect: (Int) -> Unit,
    onCardClick: (String, Int) -> Unit,
    onCardDoubleTap: (Int) -> Unit,              // <-- new
    onDeleteRequest: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Auto-scroll to the bottom when new records are appended
    LaunchedEffect(records.size, records.lastOrNull()?.logs) {
        if (records.isNotEmpty()) listState.animateScrollToItem(records.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        itemsIndexed(records) { index, record ->
            val isSelected = index == selectedIndex

            // Swipe state: when swiped start->end, call delete request and cancel actual dismiss
            val swipeState = rememberSwipeToDismissBoxState(
                confirmValueChange = { newValue ->
                    if (newValue == SwipeToDismissBoxValue.StartToEnd) {
                        onDeleteRequest(index)
                        false // prevent auto-dismiss; we handle removal via ViewModel
                    } else true
                }
            )

            // Animate corner radius and add a subtle pulse when selected and busy
            val animatedCorner by animateDpAsState(
                targetValue = if (isSelected && !canTranscribe) 48.dp else 16.dp,
                animationSpec = tween(400),
                label = "cornerAnim"
            )
            val scale = if (isSelected && !canTranscribe) {
                rememberInfiniteTransition().animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        tween(800, easing = EaseInOut),
                        RepeatMode.Reverse
                    ),
                    label = "pulse"
                ).value
            } else 1f

            // Gesture modifier attached to the inner content to avoid conflicts with swipe-to-dismiss
            val contentTapModifier = if (canTranscribe) {
                Modifier
                    .fillMaxWidth()
                    .pointerInput(index) {
                        detectTapGestures(
                            onTap = { onSelect(index) }, // single tap selects
                            onDoubleTap = {
                                onSelect(index)
                                onCardDoubleTap(index)    // <-- re-transcribe on double-tap
                            }
                        )
                    }
            } else Modifier.fillMaxWidth()

            SwipeToDismissBox(
                state = swipeState,
                modifier = Modifier.fillMaxWidth(),
                enableDismissFromStartToEnd = true,
                enableDismissFromEndToStart = false,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxSize().padding(start = 20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(scaleX = scale, scaleY = scale),
                    shape = RoundedCornerShape(animatedCorner),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            canTranscribe -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    // apply gesture detector to the content area (not the SwipeToDismiss wrapper)
                    Box(modifier = contentTapModifier.padding(16.dp)) {
                        Text(record.logs, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/* ------------------ ConfirmDeleteDialog ------------------ */

/**
 * Confirmation dialog shown before deleting a recording.
 */
@Composable
private fun ConfirmDeleteDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete Recording") },
        text = { Text("Are you sure you want to delete this recording? This action cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/* ------------------ StyledButton ------------------ */

/**
 * Common stylized button used as the primary action at the bottom.
 */
@Composable
fun StyledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(6.dp),
        modifier = modifier
    ) {
        Text(text)
    }
}

/*
変更点（日本語で簡潔に）
UI 表示の英語化
画面内のすべてのユーザー向け文字列を英語に統一しました（ダイアログ、ボタン、ラベル、削除表示など）。
コメント（KDoc / inline）を英語に統一
各Composable と主要ロジックに丁寧な英語コメントを追加しました。これはコードの可読性と再利用性を高めます。
細かい改善
RequestPermissionsIfNeeded を必ず呼ぶようにして、画面表示時に権限チェック＆リクエストを行います。
RecordingList の自動スクロールは LaunchedEffect(records.size, records.lastOrNull()?.logs) のまま残し、新規レコード追加時に末尾へスクロールします。
ConfirmDeleteDialog の文言を明確化（削除は取り消せない旨を明示）。
ConfigButtonWithDialog の UI を英語化し、言語やモデルの選択肢は例示のままにしています（必要ならモデルリストは動的に読み込み可）。
仮定・注意
myRecord の data class はファイル内に定義されていないため、このコードはプロジェクト内の既存定義に依存します。
SwipeToDismissBox / rememberSwipeToDismissBoxState と SwipeToDismissBoxValue はあなたのプロジェクトか導入済みライブラリの API を使っている想定です。もし未導入なら SwipeToDismiss の代替実装を提供します。
文字列を strings.xml に移して多言語対応（i18n）するのを推奨します。英語／日本語を切り替えたい場合、次にその変更を作ります。
 */