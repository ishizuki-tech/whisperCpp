@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.negi.stt

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.Locale

private const val LOG_TAG = "MainScreenViewModel"

/**
 * Main screen ViewModel for recording/transcription UI.
 *
 * Responsibilities:
 *  - Manage recording state and permission status
 *  - Maintain the list of recorded items (myRecords)
 *  - Load / release native whisper context and media player
 *  - Provide simple persistence for records.json
 *
 * Note: myRecord data class is assumed to be defined elsewhere in the project.
 */
class MainScreenViewModel(private val application: Application) : ViewModel() {

    // UI state (observed by Compose)
    var canTranscribe by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set
    var isModelLoading by mutableStateOf(false)
        private set
    var isConfigDialogOpen by mutableStateOf(false)
        private set
    var selectedLanguage by mutableStateOf("en")
        private set
    var selectedModel by mutableStateOf("ggml-tiny-q5_1.bin")
        private set
    var myRecords by mutableStateOf(emptyList<myRecord>())
        private set
    var translateToEnglish by mutableStateOf(false)
        private set
    var hasAllRequiredPermissions by mutableStateOf(false)
        private set

    // file locations
    private val modelsPath = File(application.filesDir, "models")
    private val recordingsPath = File(application.filesDir, "recordings")

    // native & playback handles
    private var whisperContext: com.negi.nativelib.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordedFile: File? = null

    // Recorder instance — it calls onError on main thread via the provided lambda
    private val recorder = Recorder(application) { e ->
        Log.e(LOG_TAG, "Recorder error", e)
        // Ensure state is consistent if recorder fails
        viewModelScope.launch { isRecording = false }
    }

    init {
        // Initialization: create directories, load saved records, load model, update permissions
        viewModelScope.launch {
            withContext(Dispatchers.IO) { setupDirectories() }
            // loadRecords performs IO safely
            loadRecords()
            // load default model (may be async/IO-heavy)
            loadModel(selectedModel)
            // refresh permission status
            updatePermissionsStatus()
            canTranscribe = true
        }

        // Persist myRecords when it changes (skip initial snapshot)
        viewModelScope.launch {
            var first = true
            snapshotFlow { myRecords }
                .collectLatest {
                    if (first) first = false else saveRecords()
                }
        }
    }

    /**
     * Return the list of required permissions for the current platform.
     *
     * For Android < 33 we include READ_EXTERNAL_STORAGE as legacy; for Android >= 33 we
     * include POST_NOTIFICATIONS (if you use notifications). Adjust as needed.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 33) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // if your app uses notifications, include POST_NOTIFICATIONS; otherwise remove
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    /**
     * Update hasAllRequiredPermissions flag by checking platform permissions.
     * This method is safe to call from any thread.
     */
    fun updatePermissionsStatus() {
        val required = getRequiredPermissions()
        val ok = required.all {
            ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
        }
        hasAllRequiredPermissions = ok
        Log.d(LOG_TAG, "Permissions updated: $hasAllRequiredPermissions")
    }

    // --- simple state update helpers ---

    fun updateSelectedLanguage(lang: String) { selectedLanguage = lang }
    fun updateSelectedModel(model: String) {
        selectedModel = model
        viewModelScope.launch { loadModel(model) }
    }
    fun updateTranslate(toEnglish: Boolean) { translateToEnglish = toEnglish }

    /**
     * Remove record at the given index and delete associated file.
     */
    fun removeRecordAt(index: Int) {
        if (index in myRecords.indices) {
            try {
                File(myRecords[index].absolutePath).delete()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to delete file: ${myRecords[index].absolutePath}", e)
            }
            myRecords = myRecords.toMutableList().apply { removeAt(index) }
        }
    }

    /**
     * Toggle recording state. When starting, it checks permissions and creates a new file.
     * When stopping, it waits for recorder to finish, updates the record list, and triggers transcription.
     *
     * onUpdateIndex is invoked with the latest selected index after adding a new record.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleRecord(onUpdateIndex: (Int) -> Unit) {
        Log.d(LOG_TAG, "toggleRecord invoked, hasAllRequiredPermissions=$hasAllRequiredPermissions")
        viewModelScope.launch {
            try {
                if (isRecording) {
                    // Stop recording
                    val file = currentRecordedFile
                    if (file != null) {
                        withContext(Dispatchers.IO) {
                            recorder.stopRecording()
                        }
                        isRecording = false
                        addNewRecordingLog(file.name, file.absolutePath)
                        onUpdateIndex(myRecords.lastIndex)
                        // small delay to let file flush on some devices
                        delay(200)
                        // Transcribe on background thread
                        transcribeAudio(file)
                    } else {
                        Log.w(LOG_TAG, "No currentRecordedFile when stopping!")
                        isRecording = false
                    }
                } else {
                    // Start recording
                    if (!hasAllRequiredPermissions) {
                        Log.w(LOG_TAG, "Required permissions not granted; aborting start")
                        return@launch
                    }
                    // Stop any playback before recording
                    stopPlayback()
                    val file = createNewAudioFile()
                    currentRecordedFile = file
                    withContext(Dispatchers.IO) {
                        recorder.startRecording(file)
                    }
                    isRecording = true
                    Log.d(LOG_TAG, "Recording started: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Recording error", e)
                isRecording = false
            }
        }
    }

    /**
     * Play recording at the given path. Adds a small log entry for UI feedback.
     */
    fun playRecording(path: String, index: Int) = viewModelScope.launch {
        if (!isRecording) {
            stopPlayback()
            addResultLog("▶ Playing: ${File(path).name}", index)
            startPlayback(File(path))
        }
    }

    // --- Model loading & transcription ---

    /**
     * Load the specified model into whisperContext. This is I/O heavy and runs on IO dispatcher.
     */
    private suspend fun loadModel(model: String) {
        isModelLoading = true
        canTranscribe = false
        try {
            releaseWhisperContext()
            releaseMediaPlayer()
            whisperContext = withContext(Dispatchers.IO) {
                com.negi.nativelib.WhisperContext.createContextFromAsset(
                    application.assets, "models/$model"
                )
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load model: $model", e)
        } finally {
            isModelLoading = false
            canTranscribe = true
        }
    }

    /**
     * Transcribe given audio file (WAV) using the loaded whisperContext.
     * data decoding is performed on IO dispatcher.
     */
    private suspend fun transcribeAudio(file: File, index: Int = -1) {
        if (!canTranscribe) return
        canTranscribe = false
        try {
            val data = withContext(Dispatchers.IO) { decodeWaveFile(file) }
            val result = whisperContext?.transcribeData(data, selectedLanguage, translateToEnglish)
            addResultLog("📝 $result", index)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Transcription error", e)
        } finally {
            canTranscribe = true
        }
    }

    // --- Playback helpers ---

    /**
     * Start playback on the main thread.
     */
    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri()).apply { start() }
    }

    /**
     * Stop and release media player on the main thread.
     */
    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
    }

    /**
     * Release native whisper context (IO).
     */
    private suspend fun releaseWhisperContext() = withContext(Dispatchers.IO) {
        runCatching {
            whisperContext?.release()
            whisperContext = null
        }
    }

    /**
     * Release MediaPlayer on main thread.
     */
    private suspend fun releaseMediaPlayer() = withContext(Dispatchers.Main) {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    // --- Record / log utilities ---

    /**
     * Append a new recording metadata entry to myRecords and log the timestamp.
     */
    private fun addNewRecordingLog(filename: String, path: String) {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        val log = "🎤 $filename recorded at $timestamp"
        myRecords = myRecords + myRecord(log, path)
    }

    /**
     * Append a result log entry to the specified record index (or last record if index == -1).
     */
    private fun addResultLog(text: String, index: Int) {
        val target = if (index == -1) myRecords.lastIndex else index
        if (target in myRecords.indices) {
            val updated = myRecords.toMutableList()
            updated[target] = updated[target].copy(logs = updated[target].logs + "\n$text")
            myRecords = updated
        }
    }

    /**
     * Create a new File for recording using timestamped filename.
     */
    private suspend fun createNewAudioFile(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(recordingsPath, "rec_$timestamp.wav")
    }

    /**
     * Ensure directories exist (IO).
     */
    private fun setupDirectories() {
        modelsPath.mkdirs()
        recordingsPath.mkdirs()
    }

    // --- Persistence: records.json ---

    /**
     * Persist myRecords to records.json on disk (IO dispatcher).
     */
    // --- Improved saveRecords: atomic write to tmp file then rename ---
    private fun saveRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(application.filesDir, "records.json")
                val tmp = File(application.filesDir, "records.json.tmp")

                // write to tmp
                val json = Json.encodeToString(myRecords)
                tmp.writeText(json)

                // attempt atomic replace
                if (tmp.renameTo(file).not()) {
                    // fallback: try delete+rename
                    file.delete()
                    if (!tmp.renameTo(file)) {
                        Log.e(LOG_TAG, "Failed to atomically replace records.json")
                    }
                }
                Log.d(LOG_TAG, "Saved records.json (count=${myRecords.size})")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Failed to save records", e)
            }
        }
    }
    /**
     * Load myRecords from records.json if present (IO dispatcher).
     */
    /**
     * Improved loadRecords:
     * - logs file existence and a short preview of contents
     * - attempts normal deserialization
     * - on failure, attempts simple fallback parsing from JSON structure
     * - if fallback succeeds, rewrites file in canonical format (saveRecords)
     */
    private fun loadRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(application.filesDir, "records.json")
                if (!file.exists()) {
                    Log.d(LOG_TAG, "records.json not found; nothing to load")
                    return@launch
                }

                val text = file.readText()
                Log.d(LOG_TAG, "records.json size=${text.length}")
                // log preview (avoid huge logs)
                val preview = if (text.length > 2000) text.substring(0, 2000) + "..." else text
                Log.d(LOG_TAG, "records.json preview: $preview")

                try {
                    // primary path: expected canonical format
                    val loaded: List<myRecord> = Json.decodeFromString(text)
                    withContext(Dispatchers.Main) {
                        myRecords = loaded
                    }
                    Log.d(LOG_TAG, "Loaded ${loaded.size} records from records.json")
                    return@launch
                } catch (primaryEx: Exception) {
                    Log.w(LOG_TAG, "Primary JSON decode failed, attempting fallback parsing", primaryEx)
                }

                // fallback: try to parse generically and extract fields
                try {
                    val element = Json.parseToJsonElement(text)
                    val parsed = mutableListOf<myRecord>()

                    if (element is JsonArray) {
                        element.forEach { el ->
                            if (el is JsonObject) {
                                val logs = el["logs"]?.jsonPrimitive?.contentOrNull ?: ""
                                val path = el["absolutePath"]?.jsonPrimitive?.contentOrNull ?: ""
                                parsed.add(myRecord(logs = logs, absolutePath = path))
                            }
                        }
                    } else if (element is JsonObject) {
                        // maybe saved as { "records": [...] }
                        val arr = element["records"]?.jsonArray
                        arr?.forEach { el ->
                            if (el is JsonObject) {
                                val logs = el["logs"]?.jsonPrimitive?.contentOrNull ?: ""
                                val path = el["absolutePath"]?.jsonPrimitive?.contentOrNull ?: ""
                                parsed.add(myRecord(logs = logs, absolutePath = path))
                            }
                        }
                    }

                    if (parsed.isNotEmpty()) {
                        // update state on main thread and rewrite file in canonical format
                        withContext(Dispatchers.Main) { myRecords = parsed }
                        Log.i(LOG_TAG, "Loaded ${parsed.size} records via fallback parsing. Rewriting file to canonical format.")
                        saveRecords() // rewrite canonical JSON
                        return@launch
                    } else {
                        Log.w(LOG_TAG, "Fallback parsing yielded no records")
                    }
                } catch (fallbackEx: Exception) {
                    Log.e(LOG_TAG, "Fallback parsing failed", fallbackEx)
                }

                Log.e(LOG_TAG, "Unable to load records: unknown format or corrupted file.")
            } catch (e: IOException) {
                Log.e(LOG_TAG, "I/O error while loading records", e)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Unexpected error while loading records", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Ensure resources are released when ViewModel is cleared
        viewModelScope.launch {
            // Stop recording if still active
            if (isRecording) {
                try {
                    withContext(Dispatchers.IO) { recorder.stopRecording() }
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Failed to stop recorder on clear", t)
                }
                isRecording = false
            }
            // Close/cleanup recorder (release dispatcher/resources)
            runCatching { recorder.close() }
            releaseWhisperContext()
            releaseMediaPlayer()
        }
    }

    companion object {
        /**
         * ViewModel factory to create MainScreenViewModel with Application parameter.
         */
        fun factory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(MainScreenViewModel::class.java)) {
                        return MainScreenViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
}

/*
日本語での変更点と理由（要点）
重複ログの削除
updatePermissionsStatus() に大量の重複 Log.d が入っていたため整理しました（読みやすさ向上）。
I/O をメインスレッドで行わないよう修正
saveRecords() / loadRecords() / setupDirectories() / createNewAudioFile() などファイルI/Oは Dispatchers.IO で実行するようにし、UI スレッドのブロッキングを避けました。
ViewModel の破棄時のリソース解放強化
onCleared() で録音停止・recorder.close()（リソース解放）・whisperContext / mediaPlayer の解放を確実に実行するようにしました。これでプロセス終了や画面回転でのリーク防止になります。
toggleRecord の簡潔化と安全化
ログの冗長削除、権限チェックの早期リターン、stop→transcribe の流れを明確にしました。また、録音停止時は recorder.stopRecording() を IO 上で待ち、ファイルフラッシュの猶予を小 delay で確保してから転写を開始します。
非同期処理の扱いを統一
viewModelScope.launch を基本にしつつ、IO は withContext(Dispatchers.IO) を使う形で分離しました。loadRecords() と saveRecords() は内部で viewModelScope.launch(Dispatchers.IO) を行い、呼び出し側を簡潔にしました。
エラーハンドリング
ファイル削除や I/O 時の例外をログに残すようにし、UI 状態が不整合にならないようにしています。
 */