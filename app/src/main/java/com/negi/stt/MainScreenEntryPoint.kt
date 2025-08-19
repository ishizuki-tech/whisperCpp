@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.negi.stt

import android.Manifest
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.*

/**
 * MainScreenEntryPoint
 * --------------------------------------------------------
 * アプリのホーム画面エントリーポイント。
 * - 起動時に必要なパーミッションを確認＆リクエスト
 * - 選択中インデックスはローカルStateで管理
 */
@RequiresPermission(Manifest.permission.RECORD_AUDIO)
@Composable
fun MainScreenEntryPoint(viewModel: MainScreenViewModel) {

    var selectedIndex: Int by remember { mutableIntStateOf(-1) }

    MainScreen(
        viewModel = viewModel,
        canTranscribe = viewModel.canTranscribe,
        isRecording = viewModel.isRecording,
        selectedIndex = selectedIndex,
        onSelect = { selectedIndex = it },
        onRecordTapped = {
            selectedIndex = viewModel.myRecords.lastIndex
            viewModel.toggleRecord { selectedIndex = it }
        },
        onCardClick = viewModel::playRecording
    )
}


