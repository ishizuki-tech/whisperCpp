package com.negi.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * PCM recording -> WAV writer utility.
 *
 * Usage:
 *  - call startRecording(outputFile) to begin recording (suspending)
 *  - call stopRecording() to stop and produce the wav file (suspending)
 *
 * Behavior notes:
 *  - AudioRecord is kept as a class-level reference so stopRecording() can call stop() to
 *    unblock a blocking read().
 *  - PCM is written to a temp file in cacheDir, then converted to WAV on stop.
 *  - WAV header size is checked to avoid 32-bit overflow.
 */
class Recorder(
    private val context: Context,
    private val onError: (Exception) -> Unit
) {
    private val handler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in Recorder coroutine", throwable)
        notifyError(throwable as? Exception ?: RuntimeException(throwable))
    }

    // Dedicated single-threaded dispatcher for recording I/O and AudioRecord usage.
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + handler)

    private var job: Job? = null
    private val recordingFlag = AtomicBoolean(false)

    // Shared state: access guarded by mutex to avoid races.
    private val stateMutex = Mutex()
    private var tempPcmFile: File? = null
    private var targetWavFile: File? = null
    private var currentConfig: ValidConfig? = null

    // Keep audioRecord at class level so stopRecording() can call stop/release.
    @Volatile
    private var audioRecord: AudioRecord? = null

    /**
     * Release internal resources. Call when this object is no longer needed.
     */
    fun close() {
        try {
            runBlocking {
                // Ensure AudioRecord stopped and job cancelled on the single-threaded dispatcher.
                stateMutex.withLock {
                    audioRecord?.let {
                        try {
                            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                it.stop()
                            }
                        } catch (_: Throwable) { /* ignore */ }
                        try { it.release() } catch (_: Throwable) {}
                        audioRecord = null
                    }
                }
                job?.cancelAndJoin()
                job = null
            }
        } catch (t: Throwable) {
            // ignore
        } finally {
            // Close underlying dispatcher to avoid thread leak.
            try { dispatcher.close() } catch (_: Throwable) {}
        }
    }

    /** Returns true if currently recording. */
    fun isRecording(): Boolean = recordingFlag.get()

    /**
     * Start recording to a WAV target file. PCM is written to a temp file until stopRecording().
     * This is a suspending function and does initialization on IO dispatcher.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun startRecording(outputFile: File) = withContext(Dispatchers.IO) {
        if (recordingFlag.get()) {
            Log.w(TAG, "startRecording() ignored — already recording")
            return@withContext
        }
        try {
            Log.d(TAG, "startRecording() -> ${outputFile.absolutePath}")

            // Basic environment & permission checks.
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                throw IllegalStateException("No microphone available")
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                throw IllegalStateException("RECORD_AUDIO permission not granted")
            }

            // Find a working AudioRecord configuration.
            val config = findValidAudioConfig()
                ?: throw IllegalStateException("No valid AudioRecord config found")

            // Save config and target under mutex.
            stateMutex.withLock {
                currentConfig = config
                targetWavFile = outputFile
            }

            // Create a temporary PCM file in cache directory.
            val tmp = File.createTempFile("rec_", ".pcm", context.cacheDir)
            stateMutex.withLock { tempPcmFile = tmp }

            // Build and store AudioRecord instance for cross-method control.
            val ar = buildAudioRecord(config)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioRecord init failed state=${ar.state}")
            }
            stateMutex.withLock { audioRecord = ar }

            recordingFlag.set(true)

            // Launch the recording loop on the dedicated dispatcher.
            job = scope.launch {
                FileOutputStream(tmp).use { fos ->
                    try {
                        ar.startRecording()
                        Log.i(TAG, "Recording started: rate=${config.sampleRate}, buf=${config.bufferSize}")

                        // Prepare buffers for batch write (short -> little-endian bytes).
                        val shortBuf = ShortArray(config.bufferSize / 2)
                        val byteBuf = ByteArray(shortBuf.size * 2)

                        while (isActive && recordingFlag.get()) {
                            // read returns number of samples (shorts)
                            val read = ar.read(shortBuf, 0, shortBuf.size)
                            if (read > 0) {
                                // Convert shorts to little-endian bytes in batch.
                                var bi = 0
                                for (i in 0 until read) {
                                    val s = shortBuf[i].toInt()
                                    byteBuf[bi++] = (s and 0xFF).toByte()
                                    byteBuf[bi++] = ((s shr 8) and 0xFF).toByte()
                                }
                                fos.write(byteBuf, 0, read * 2)
                            } else if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                                throw RuntimeException("AudioRecord.read error code: $read")
                            } else {
                                // If read == 0, continue loop.
                            }
                        }

                        // Try to stop the AudioRecord gracefully.
                        try {
                            if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                ar.stop()
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to stop AudioRecord gracefully", t)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in recording loop", t)
                        notifyError(t as? Exception ?: RuntimeException(t))
                    } finally {
                        try { ar.release() } catch (_: Throwable) {}
                        stateMutex.withLock { audioRecord = null }
                    }
                }
            }
        } catch (e: Exception) {
            notifyError(e)
            cleanupTemp()
            recordingFlag.set(false)
            stateMutex.withLock {
                audioRecord?.let { try { it.release() } catch (_: Throwable) {} ; audioRecord = null }
            }
        }
    }

    /**
     * Stop recording and write the WAV file from the temp PCM file.
     * This is a suspending function and runs on IO dispatcher.
     */
    suspend fun stopRecording() = withContext(Dispatchers.IO) {
        if (!recordingFlag.get()) {
            Log.w(TAG, "stopRecording() ignored — not recording")
            return@withContext
        }

        recordingFlag.set(false)
        try {
            // Call stop() to unblock a possible blocking read().
            stateMutex.withLock {
                audioRecord?.let {
                    try {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    } catch (t: Throwable) {
                        Log.w(TAG, "audioRecord.stop() failed", t)
                    }
                }
            }

            // Wait for the recording job to finish.
            job?.cancelAndJoin()
            job = null

            // Snapshot state under mutex.
            val pcm: File?
            val wav: File?
            val cfg: ValidConfig?
            stateMutex.withLock {
                pcm = tempPcmFile
                wav = targetWavFile
                cfg = currentConfig
            }

            if (pcm == null || wav == null || cfg == null) {
                throw IllegalStateException("Incomplete state — cannot write WAV")
            }

            writeWavFromPcm(pcm, wav, cfg.sampleRate, 1, 16)
            Log.i(TAG, "WAV written: ${wav.absolutePath}")
        } catch (e: Exception) {
            notifyError(e)
        } finally {
            cleanupTemp()
            // Ensure audioRecord released.
            stateMutex.withLock {
                audioRecord?.let {
                    try { it.release() } catch (_: Throwable) {}
                    audioRecord = null
                }
            }
        }
    }

    // Dispatch an error to main thread.
    private fun notifyError(e: Exception) {
        CoroutineScope(Dispatchers.Main).launch { onError(e) }
    }

    // Cleanup temp state under mutex.
    private fun cleanupTemp() {
        runBlocking {
            stateMutex.withLock {
                try { tempPcmFile?.delete() } catch (_: Throwable) {}
                tempPcmFile = null
                targetWavFile = null
                currentConfig = null
            }
        }
    }

    // ====== AudioRecord Config ======

    private data class ValidConfig(
        val sampleRate: Int,
        val channelConfig: Int,
        val audioFormat: Int,
        val bufferSize: Int
    )

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun findValidAudioConfig(): ValidConfig? {
        val sampleRates = intArrayOf(16000, 44100, 48000)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        for (rate in sampleRates) {
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBuf <= 0) continue

            val bufferSize = minBuf * 2
            val format = AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(rate)
                .setChannelMask(channelConfig)
                .build()

            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return ValidConfig(rate, channelConfig, audioFormat, bufferSize)
            } else {
                recorder.release()
            }
        }
        return null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildAudioRecord(cfg: ValidConfig): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(cfg.audioFormat)
            .setSampleRate(cfg.sampleRate)
            .setChannelMask(cfg.channelConfig)
            .build()

        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(cfg.bufferSize)
            .build()
    }

    // ====== PCM -> WAV ======
    private fun writeWavFromPcm(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int
    ) {
        // Check PCM size doesn't overflow 32-bit WAV header fields.
        val pcmSizeLong = pcmFile.length()
        if (pcmSizeLong > Int.MAX_VALUE) {
            throw IllegalStateException("PCM too large to write WAV header (size=${pcmSizeLong}). WAV RIFF uses 32-bit chunk sizes. Use RF64 or chunked strategy for files >2GB.")
        }
        val pcmSize = pcmSizeLong.toInt()
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val totalDataLen = 36 + pcmSize

        FileOutputStream(wavFile).use { out ->
            DataOutputStream(out).use { dos ->
                // Write ASCII headers explicitly.
                dos.write("RIFF".toByteArray(Charsets.US_ASCII))
                dos.writeIntLE(totalDataLen)
                dos.write("WAVE".toByteArray(Charsets.US_ASCII))
                dos.write("fmt ".toByteArray(Charsets.US_ASCII))
                dos.writeIntLE(16)                    // Subchunk1Size = 16
                dos.writeShortLE(1.toShort())         // AudioFormat = 1 (PCM)
                dos.writeShortLE(numChannels.toShort())
                dos.writeIntLE(sampleRate)
                dos.writeIntLE(byteRate)
                dos.writeShortLE((numChannels * bitsPerSample / 8).toShort()) // BlockAlign
                dos.writeShortLE(bitsPerSample.toShort())                     // BitsPerSample
                dos.write("data".toByteArray(Charsets.US_ASCII))
                dos.writeIntLE(pcmSize)

                FileInputStream(pcmFile).use { fis ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        dos.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    // Little-endian write helpers.
    private fun DataOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Short) {
        val v = value.toInt()
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    companion object {
        private const val TAG = "Recorder"
    }
}

/*
日本語の説明（簡潔）
主な目的: 元の実装の堅牢性とパフォーマンスを向上させ、AudioRecord.read() のブロッキングによるキャンセル問題や、WAVヘッダのオーバーフロー、サンプルごとの非効率な書き込みを解消しました。
具体的な変更点
audioRecord をクラスレベルの @Volatile フィールドに移動し、stopRecording() から stop() を呼べるようにしました。これにより read() がブロックしたまま止まらない問題を緩和します。
PCM 書き込みを「short -> byte のバッチ変換」で行い、1サンプルごとの write() をやめて I/O を高速化しました。
共有状態（temp ファイル、ターゲットファイル、設定）へのアクセスを Mutex で保護して競合を防ぎました。
WAV ヘッダのサイズフィールドが 32-bit に収まるかをチェックし、超過する場合は明確な例外を投げます（RF64 対応の必要性を明示）。
単一スレッドの専用ディスパッチャを使い、録音関連の処理を同じスレッド上で扱うことでスレッド間問題を減らしました。close() を用意してディスパッチャのクローズなどリソース解放が可能です。
注意点 / 追加提案
長時間録音（>2GB）を扱う必要がある場合は RF64 やチャンク分割実装が必要です。
API レベル互換（API < 23）をサポートするなら AudioRecord のフォールバックパスを追加してください。
onError のコール回数やログのフォーマットはお好みで調整可能です。
さらに低レイテンシな停止を優先する場合は、ネイティブ側やデバイス固有の挙動に応じた追加のアンロック手法（非同期通知／エベントFD等）を検討してください。
 */