package com.negi.nativelib

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "Whisper"

/**
 * WhisperLib
 *
 * JNI bindings + native library loader.
 *
 * This object selects and loads an optimized native library variant based on
 * the device ABI and /proc/cpuinfo features (e.g. vfpv4, fp16). All JNI
 * declarations live here as @JvmStatic externals.
 */
private object WhisperLib {
    init {
        // Log primary ABI for diagnostics.
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        Log.d(LOG_TAG, "Primary ABI: $abi")

        // Try to read CPU info to detect CPU features (optional; may be null on some devices).
        val cpuInfo = readCpuInfo()

        // Choose an optimized native library where possible.
        when {
            isArmEabiV7a() && cpuInfo?.contains("vfpv4") == true -> {
                Log.d(LOG_TAG, "Detected armeabi-v7a + vfpv4 → loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            }
            isArmEabiV8a() && cpuInfo?.contains("fphp") == true -> {
                Log.d(LOG_TAG, "Detected arm64-v8a + fp16 → loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            }
            else -> {
                Log.d(LOG_TAG, "Fallback → loading default libwhisper.so")
                System.loadLibrary("whisper")
            }
        }
    }

    // =======================
    // JNI function declarations
    // =======================
    @JvmStatic external fun initContextFromInputStream(inputStream: InputStream): Long
    @JvmStatic external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(contextPtr: Long)

    @JvmStatic external fun fullTranscribe(
        contextPtr: Long,
        lang: String,
        numThreads: Int,
        translate: Boolean,
        audioData: FloatArray
    )

    @JvmStatic external fun getTextSegmentCount(contextPtr: Long): Int
    @JvmStatic external fun getTextSegment(contextPtr: Long, index: Int): String
    @JvmStatic external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    @JvmStatic external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    @JvmStatic external fun getSystemInfo(): String
    @JvmStatic external fun benchMemcpy(nthread: Int): String
    @JvmStatic external fun benchGgmlMulMat(nthread: Int): String
}

/**
 * WhisperContext
 *
 * A safe wrapper around the native Whisper context pointer (ptr).
 *
 * Key points:
 *  - Whisper.cpp is generally NOT thread-safe for concurrent inference, so we
 *    run all native calls on a dedicated single-threaded executor.
 *  - Callers must release the native resources by calling [release] or by using
 *    Kotlin's use/try-with-resources via [close].
 */
class WhisperContext private constructor(
    private var ptr: Long
) : AutoCloseable {

    // Dedicated single-threaded dispatcher for all native calls.
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())

    /**
     * Transcribe PCM float data via native whisper.
     *
     * @param data PCM audio as FloatArray (expected sample format should match native expectation)
     * @param lang language code (e.g. "en", "ja", "sw")
     * @param translate whether to run translation
     * @param printTimestamp if true, include [T0 - T1] timestamps for each segment in the returned text
     *
     * Note: This function dispatches the native calls to the dedicated single-threaded dispatcher
     * to avoid concurrent access to the native context.
     */
    suspend fun transcribeData(
        data: FloatArray,
        lang: String,
        translate: Boolean,
        printTimestamp: Boolean = true
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L) { "WhisperContext already released" }

        // Choose thread count from your app configuration.
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Whisper inference: threads=$numThreads, lang=$lang, translate=$translate")

        // Call native fullTranscribe (this will populate internal native buffers / segments).
        WhisperLib.fullTranscribe(ptr, lang, numThreads, translate, data)

        // Read out text segments and optionally include timestamps.
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        val sb = StringBuilder()
        for (i in 0 until textCount) {
            if (printTimestamp) {
                val t0 = WhisperLib.getTextSegmentT0(ptr, i)
                val t1 = WhisperLib.getTextSegmentT1(ptr, i)
                sb.append("[${toTimestamp(t0)} - ${toTimestamp(t1)}] ")
            }
            sb.append(WhisperLib.getTextSegment(ptr, i))
        }
        sb.toString()
    }

    /**
     * Memory copy benchmark wrapper.
     * Runs on the same single-threaded dispatcher as inference.
     */
    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        WhisperLib.benchMemcpy(nthreads)
    }

    /**
     * Matrix multiplication benchmark wrapper.
     */
    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        WhisperLib.benchGgmlMulMat(nthreads)
    }

    /**
     * Release native resources.
     *
     * This will free the native context (if any), cancel the internal coroutine scope,
     * and shut down the dedicated executor/dispatcher.
     *
     * It is safe to call multiple times.
     */
    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            try {
                WhisperLib.freeContext(ptr)
                Log.d(LOG_TAG, "WhisperContext: released native resources")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Error while freeing native context", e)
            } finally {
                ptr = 0L
            }
        }

        // Cancel coroutine work and shutdown the dispatcher/executor.
        scope.cancel()
        try {
            dispatcher.close()
        } catch (ignore: Throwable) {
            // Some coroutine versions may not have close; ignore failures.
        }
        try {
            executor.shutdownNow()
        } catch (ignore: Throwable) {
        }
    }

    /**
     * AutoCloseable implementation to support `use {}` style.
     * It blocks until release completes.
     */
    override fun close() {
        runBlocking {
            release()
        }
    }

    companion object {
        /**
         * Create context by loading model from a file path.
         * Throws IllegalArgumentException if native init returns 0.
         */
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            require(ptr != 0L) { "Couldn't create context from file: $filePath" }
            return WhisperContext(ptr)
        }

        /**
         * Create context from an InputStream.
         * Note: native side must consume the stream fully.
         */
        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            require(ptr != 0L) { "Couldn't create context from input stream" }
            return WhisperContext(ptr)
        }

        /**
         * Create context from an asset inside the APK.
         *
         * @param assetManager application assets
         * @param assetPath path to the model file within assets (e.g. "models/whisper.bin")
         */
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            require(ptr != 0L) { "Couldn't create context from asset: $assetPath" }
            return WhisperContext(ptr)
        }

        /** Return build / system info string provided by native lib. */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

/* ============================
   Utility functions
   ============================ */

/** Return true if primary ABI appears to be armeabi-v7a. */
private fun isArmEabiV7a(): Boolean =
    Build.SUPPORTED_ABIS.firstOrNull() == "armeabi-v7a"

/** Return true if primary ABI appears to be arm64-v8a. */
private fun isArmEabiV8a(): Boolean =
    Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

/**
 * Read /proc/cpuinfo if available. Returns null on failure.
 * Useful for detecting CPU features (e.g. vfpv4, fphp).
 */
private fun readCpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}

/**
 * Convert time represented in *10ms units* to "hh:mm:ss.SSS" (or with comma).
 *
 * Many whisper-like native APIs return timestamps in units of 10ms; the original
 * code multiplied by 10 to obtain milliseconds — we preserve that contract here.
 *
 * @param t time in 10ms units
 * @param comma if true use comma as the milliseconds delimiter (e.g. "00:00:01,234")
 */
fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10 // t = count of 10ms frames -> convert to ms
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}
