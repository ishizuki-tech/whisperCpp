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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val LOG_TAG = "MainScreenViewModel"

/**
 * ViewModel for the main recording / transcription screen.
 *
 * Responsibilities:
 *  - keep UI-observable state for recording/transcription controls
 *  - manage persistent list of recordings (atomic write + fsync)
 *  - hold and release native resources (Whisper context, MediaPlayer)
 *  - provide safe start/stop recording flows
 *
 * Note: This file expects types like MyRecord, Recorder and com.negi.nativelib.WhisperContext
 * to be available elsewhere in the project.
 */
class MainScreenViewModel(private val application: Application) : ViewModel() {

    // ----- UI-observed state -----
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

    var myRecords by mutableStateOf(emptyList<MyRecord>())
        private set

    var translateToEnglish by mutableStateOf(false)
        private set

    var hasAllRequiredPermissions by mutableStateOf(false)
        private set

    // ----- file & resource locations -----
    private val modelsPath = File(application.filesDir, "models")
    private val recordingsPath = File(application.filesDir, "recordings")

    // ----- native & playback handles -----
    private var whisperContext: com.negi.nativelib.WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordedFile: File? = null

    // Recorder instance (calls error callback on main thread)
    private val recorder = Recorder(application) { e ->
        Log.e(LOG_TAG, "Recorder error", e)
        viewModelScope.launch { isRecording = false }
    }

    // Reusable JSON formatter for persistence
    private val jsonFormatter = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    // Counter used for save logging/debugging
    private val saveCounter = AtomicInteger(0)

    init {
        // Create dirs, load records, attempt to load default model, update permissions
        viewModelScope.launch {
            withContext(Dispatchers.IO) { setupDirectories() }
            loadRecords()
            loadModel(selectedModel)
            updatePermissionsStatus()
            canTranscribe = true
        }

        // Persist myRecords on change (skip the initial emission)
        viewModelScope.launch {
            var first = true
            snapshotFlow { myRecords }
                .collectLatest { current ->
                    Log.d(
                        LOG_TAG,
                        "snapshotFlow: change observed (first=$first) size=${current.size}"
                    )
                    if (first) first = false else saveRecords()
                }
        }
    }

    // ----------------------
    // Permissions utilities
    // ----------------------

    /**
     * Returns required permissions depending on platform SDK.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 33) {
            // Older platforms may need external storage read permission
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            // Android 13+ requires notifications permission if the app uses notifications
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    /**
     * Updates hasAllRequiredPermissions flag by checking all required permissions.
     */
    fun updatePermissionsStatus() {
        val required = getRequiredPermissions()
        val ok = required.all {
            ContextCompat.checkSelfPermission(application, it) == PackageManager.PERMISSION_GRANTED
        }
        hasAllRequiredPermissions = ok
        Log.d(LOG_TAG, "Permissions updated: $hasAllRequiredPermissions")
    }

    // ----------------------
    // Simple state setters
    // ----------------------

    fun updateSelectedLanguage(lang: String) {
        selectedLanguage = lang
    }

    fun updateSelectedModel(model: String) {
        selectedModel = model
        viewModelScope.launch { loadModel(model) }
    }

    fun updateTranslate(toEnglish: Boolean) {
        translateToEnglish = toEnglish
    }

    // ----------------------
    // Record list management
    // ----------------------

    /**
     * Remove record at index and delete its associated file.
     */
    fun removeRecordAt(index: Int) {
        if (index in myRecords.indices) {
            try {
                File(myRecords[index].absolutePath).delete()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to delete file: ${myRecords[index].absolutePath}", e)
            }
            myRecords = myRecords.toMutableList().apply { removeAt(index) }
            saveRecords() // persist change immediately
        }
    }

    // ----------------------
    // Recording control
    // ----------------------

    /**
     * Toggle recording state. If recording is stopped, add record and start transcription.
     * Caller must ensure RECORD_AUDIO permission is granted when calling this method.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleRecord(onUpdateIndex: (Int) -> Unit) {
        Log.d(LOG_TAG, "toggleRecord invoked, hasAllRequiredPermissions=$hasAllRequiredPermissions")
        viewModelScope.launch {
            try {
                if (isRecording) {
                    // Stop recording path
                    val file = currentRecordedFile
                    if (file != null) {
                        withContext(Dispatchers.IO) {
                            recorder.stopRecording()
                        }
                        isRecording = false
                        addNewRecordingLog(file.name, file.absolutePath)
                        onUpdateIndex(myRecords.lastIndex)
                        // small delay to ensure file flush before transcription
                        delay(200)
                        transcribeAudio(file)
                    } else {
                        Log.w(LOG_TAG, "No currentRecordedFile when stopping!")
                        isRecording = false
                    }
                } else {
                    // Start recording path
                    if (!hasAllRequiredPermissions) {
                        Log.w(LOG_TAG, "Required permissions not granted; aborting start")
                        return@launch
                    }
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

    // ----------------------
    // Playback helpers
    // ----------------------

    /**
     * Play the recording located at [path]. Adds a short result log.
     */
    fun playRecording(path: String, index: Int) = viewModelScope.launch {
        if (!isRecording) {
            stopPlayback()
            addResultLog("▶ Playing: ${File(path).name}", index)
            startPlayback(File(path))
        }
    }

    // ----------------------
    // Model & transcription
    // ----------------------

    /**
     * Load a native whisper model from the app assets. This is a suspend method.
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
     * Transcribe audio file using the loaded whisper context.
     * If index != -1, the result will be appended to the corresponding record's logs.
     */
    private suspend fun transcribeAudio(file: File, index: Int = -1) {
        if (!canTranscribe) return
        canTranscribe = false
        try {
            val data = readAudioSamples(file)
            val start = System.currentTimeMillis()
            val result = whisperContext?.transcribeData(data, selectedLanguage, translateToEnglish)
            val elapsedMs = System.currentTimeMillis() - start
            val seconds = elapsedMs / 1000
            val milliseconds = elapsedMs % 1000
            val resultText = buildString {
                appendLine("✅ Done.")
                appendLine("🕒 Finished in ${seconds}.${"%03d".format(milliseconds)}s")
                appendLine("🎯 Model     : $selectedModel")
                appendLine("🌐 Language  : $selectedLanguage")
                if (translateToEnglish) appendLine("🌐 Translate To Eng")
                appendLine("📝 Converted Text Result")
                appendLine(result ?: "")
            }
            addResultLog(resultText, index)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Transcription error", e)
        } finally {
            canTranscribe = true
        }
    }

    /**
     * Read audio samples from a WAV file. This method stops playback first then starts it
     * (so the user hears the file during transcribe flow).
     */
    suspend fun readAudioSamples(file: File): FloatArray {
        stopPlayback()
        startPlayback(file)
        return withContext(Dispatchers.IO) {
            // decodeWaveFile is expected to be provided elsewhere
            decodeWaveFile(file)
        }
    }

    /**
     * Re-transcribe existing recording by index.
     */
    fun transcribeRecording(index: Int) = viewModelScope.launch {
        try {
            if (index !in myRecords.indices) {
                Log.w(LOG_TAG, "transcribeRecording: invalid index $index")
                return@launch
            }
            val path = myRecords[index].absolutePath
            val file = File(path)
            if (!file.exists()) {
                Log.w(LOG_TAG, "transcribeRecording: file not found $path")
                addResultLog("⚠️ File not found: ${file.name}", index)
                return@launch
            }
            addResultLog("⏳ Re-transcribing...", index)
            transcribeAudio(file, index)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "transcribeRecording failed", e)
            addResultLog("⚠️ Transcription error: ${e.message}", index)
        }
    }

    /**
     * Start playback of [file] on the main thread.
     */
    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
        // MediaPlayer.create(context, uri) convenience is used here
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri()).apply { start() }
    }

    /**
     * Stop and release current media player on main thread.
     */
    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        releaseMediaPlayer()
    }

    /**
     * Release native whisper context on IO dispatcher.
     */
    private suspend fun releaseWhisperContext() = withContext(Dispatchers.IO) {
        runCatching {
            whisperContext?.release()
            whisperContext = null
        }
    }

    /**
     * Release media player on main dispatcher.
     */
    private suspend fun releaseMediaPlayer() = withContext(Dispatchers.Main) {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }

    // ----------------------
    // Record/log utilities
    // ----------------------

    /**
     * Add a newly-created recording entry to the list and attempt immediate persistence.
     */
    private fun addNewRecordingLog(filename: String, path: String) {
        val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
        val log = "🎤 $filename recorded at $timestamp"
        myRecords = myRecords + MyRecord(log, path)

        // Attempt to persist immediately on IO dispatcher
        viewModelScope.launch(Dispatchers.IO) {
            val ok = saveRecordsInternal()
            if (!ok) {
                Log.e(LOG_TAG, "addNewRecordingLog: immediate save failed")
            } else {
                Log.d(LOG_TAG, "addNewRecordingLog: immediate save succeeded")
            }
        }
    }

    /**
     * Append a textual log to a record's logs field and persist.
     * If index is -1, use the last record.
     */
    private fun addResultLog(text: String, index: Int) {
        val target = if (index == -1) myRecords.lastIndex else index
        if (target in myRecords.indices) {
            val updated = myRecords.toMutableList()
            updated[target] = updated[target].copy(logs = updated[target].logs + "\n$text")
            myRecords = updated
            saveRecords()
        }
    }

    /**
     * Create new audio file name in recordings directory.
     */
    private suspend fun createNewAudioFile(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        File(recordingsPath, "rec_$timestamp.wav")
    }

    private fun setupDirectories() {
        modelsPath.mkdirs()
        recordingsPath.mkdirs()
    }

    // ----------------------
    // Persistence (atomic + fsync)
    // ----------------------

    /**
     * Write bytes atomically: create temp file in same dir, fsync, then rename.
     * Returns true on success.
     */
    private fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val dir = target.parentFile ?: application.filesDir
        val tmp = try {
            File.createTempFile("records", ".tmp", dir)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "writeAtomic: failed to create temp file", e)
            return false
        }

        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
                fos.flush()
            }

            // Try rename, attempt to remove existing target first if needed
            if (tmp.renameTo(target)) {
                return true
            } else {
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) return true
                Log.e(
                    LOG_TAG,
                    "writeAtomic: renameTo failed (tmp=${tmp.absolutePath} -> target=${target.absolutePath})"
                )
                return false
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "writeAtomic: write failed", e)
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
            return false
        }
    }

    /**
     * Internal save routine. Must be called on IO dispatcher or from a coroutine.
     * Returns true on success.
     */
    private suspend fun saveRecordsInternal(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(application.filesDir, "records.json")

            // Provide explicit serializer type to aid compiler type inference
            val listSer: KSerializer<List<MyRecord>> = ListSerializer(MyRecord.serializer())
            // Use explicit serializer first-arg to avoid inference failure
            val jsonText = jsonFormatter.encodeToString(listSer, myRecords)
            val bytes = jsonText.toByteArray(Charsets.UTF_8)

            val ok = writeAtomic(file, bytes)
            if (!ok) Log.e(LOG_TAG, "saveRecordsInternal: writeAtomic failed")
            ok
        } catch (e: Exception) {
            Log.e(LOG_TAG, "saveRecordsInternal: unexpected error", e)
            false
        }
    }

    /**
     * Public non-blocking wrapper for saving records. Schedules IO job.
     */
    private fun saveRecords() {
        val id = saveCounter.incrementAndGet()
        Log.d(
            LOG_TAG,
            "saveRecords() called #$id - scheduling IO write (records.size=${myRecords.size})"
        )
        viewModelScope.launch {
            val ok = saveRecordsInternal()
            if (!ok) Log.e(LOG_TAG, "saveRecords#$id: failed")
        }
    }

    /**
     * Blocking save method for debugging. Do not call on UI thread.
     */
    fun saveRecordsSync(): Boolean {
        return try {
            val file = File(application.filesDir, "records.json")
            // Use explicit serializer here too
            val listSer: KSerializer<List<MyRecord>> = ListSerializer(MyRecord.serializer())
            val jsonText = jsonFormatter.encodeToString(listSer, myRecords)
            val bytes = jsonText.toByteArray(Charsets.UTF_8)

            val free = application.filesDir.usableSpace
            val required = bytes.size + 4096L
            if (free < required) {
                Log.e(
                    LOG_TAG,
                    "saveRecordsSync: not enough free space (need=$required, free=$free)"
                )
                return false
            }

            val ok = writeAtomic(file, bytes)
            if (ok) Log.d(LOG_TAG, "saveRecordsSync: wrote records.json (count=${myRecords.size})")
            else Log.e(LOG_TAG, "saveRecordsSync: writeAtomic failed")
            ok
        } catch (e: Exception) {
            Log.e(LOG_TAG, "saveRecordsSync: failed", e)
            false
        }
    }

    /**
     * Load records.json with a primary decode path and a robust fallback parser that
     * tolerates older or different shapes of the file.
     */
    private fun loadRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(application.filesDir, "records.json")
                if (!file.exists()) {
                    Log.d(LOG_TAG, "loadRecords: records.json not found")
                    return@launch
                }

                val text = file.readText()
                Log.d(LOG_TAG, "loadRecords: records.json size=${text.length}")
                val preview = if (text.length > 2000) text.substring(0, 2000) + "..." else text
                Log.d(LOG_TAG, "loadRecords preview: $preview")

                try {
                    val loaded: List<MyRecord> = jsonFormatter.decodeFromString(text)
                    withContext(Dispatchers.Main) { myRecords = loaded }
                    Log.d(LOG_TAG, "loadRecords: loaded ${loaded.size} records (primary)")
                    return@launch
                } catch (primaryEx: Exception) {
                    Log.w(
                        LOG_TAG,
                        "loadRecords: primary decode failed, will attempt fallback",
                        primaryEx
                    )
                }

                // Fallback parsing: handle array of objects or { "records": [...] } shape.
                try {
                    val element = jsonFormatter.parseToJsonElement(text)
                    val parsed = mutableListOf<MyRecord>()

                    when (element) {
                        is JsonArray -> {
                            element.forEach { el ->
                                if (el is JsonObject) {
                                    val logs =
                                        el["logs"]?.let { if (it is JsonPrimitive) it.content else "" }
                                            ?: ""
                                    val path =
                                        el["absolutePath"]?.let { if (it is JsonPrimitive) it.content else "" }
                                            ?: ""
                                    parsed.add(MyRecord(logs = logs, absolutePath = path))
                                }
                            }
                        }

                        is JsonObject -> {
                            val arr = element["records"]?.jsonArray
                            arr?.forEach { el ->
                                if (el is JsonObject) {
                                    val logs =
                                        el["logs"]?.let { if (it is JsonPrimitive) it.content else "" }
                                            ?: ""
                                    val path =
                                        el["absolutePath"]?.let { if (it is JsonPrimitive) it.content else "" }
                                            ?: ""
                                    parsed.add(MyRecord(logs = logs, absolutePath = path))
                                }
                            }
                        }

                        else -> {}
                    }

                    if (parsed.isNotEmpty()) {
                        withContext(Dispatchers.Main) { myRecords = parsed }
                        Log.i(
                            LOG_TAG,
                            "loadRecords: loaded ${parsed.size} records via fallback. Rewriting canonical file."
                        )
                        saveRecords()
                        return@launch
                    } else {
                        Log.w(LOG_TAG, "loadRecords: fallback parsing yielded no records")
                    }
                } catch (fallbackEx: Exception) {
                    Log.e(LOG_TAG, "loadRecords: fallback parsing failed", fallbackEx)
                }

                Log.e(
                    LOG_TAG,
                    "loadRecords: Unable to load records - unknown format or corrupted file."
                )
            } catch (e: IOException) {
                Log.e(LOG_TAG, "loadRecords: I/O error while loading records", e)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "loadRecords: unexpected error", e)
            }
        }
    }

    // ----------------------
    // Lifecycle cleanup
    // ----------------------

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            if (isRecording) {
                try {
                    withContext(Dispatchers.IO) { recorder.stopRecording() }
                } catch (t: Throwable) {
                    Log.w(LOG_TAG, "Failed to stop recorder on clear", t)
                }
                isRecording = false
            }
            runCatching { recorder.close() }
            releaseWhisperContext()
            releaseMediaPlayer()
        }
    }

    // ----------------------
    // ViewModel factory
    // ----------------------

    companion object {
        /**
         * Factory for creating MainScreenViewModel with Application parameter.
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
