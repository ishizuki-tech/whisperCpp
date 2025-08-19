package com.negi.nativelib
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "Whisper"

/* ======================================================================
   WhisperLib (JNI バインディング + .so ロード判定)
   ====================================================================== */
private class WhisperLib private constructor() {
    companion object {
        init {
            // ABI 情報をログ
            val abi = Build.SUPPORTED_ABIS.getOrNull(0) ?: "unknown"
            Log.d(LOG_TAG, "Primary ABI: $abi")

            // CPU feature を読み込み
            val cpuInfo = readCpuInfo()

            // 最適化ライブラリをロード
            when {
                isArmEabiV7a() && cpuInfo?.contains("vfpv4") == true -> {
                    Log.d(LOG_TAG, "Detected armeabi-v7a + vfpv4 → load libwhisper_vfpv4.so")
                    System.loadLibrary("whisper_vfpv4")
                }
                isArmEabiV8a() && cpuInfo?.contains("fphp") == true -> {
                    Log.d(LOG_TAG, "Detected arm64-v8a + fp16 → load libwhisper_v8fp16_va.so")
                    System.loadLibrary("whisper_v8fp16_va")
                }
                else -> {
                    Log.d(LOG_TAG, "Fallback → load default libwhisper.so")
                    System.loadLibrary("whisper")
                }
            }
        }

        // ========== JNI bindings ==========

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
}

/* ======================================================================
   WhisperContext (安全ラッパークラス)
   ====================================================================== */
class WhisperContext private constructor(
    private var ptr: Long
) : AutoCloseable {

    // Whisper.cpp は「同時アクセス禁止」なので専用スレッドを1本確保
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope: CoroutineScope = CoroutineScope(dispatcher)

    /**
     * PCM音声データをWhisperで推論し、テキスト化して返す
     */
    suspend fun transcribeData(
        data: FloatArray,
        lang: String,
        translate: Boolean,
        printTimestamp: Boolean = true
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L) { "WhisperContext already released" }
        val numThreads = WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Whisper推論: threads=$numThreads, lang=$lang, translate=$translate")
        WhisperLib.fullTranscribe(ptr, lang, numThreads, translate, data)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        buildString {
            for (i in 0 until textCount) {
                append(WhisperLib.getTextSegment(ptr, i))
            }
        }
    }

    /** メモリコピー速度ベンチ */
    suspend fun benchMemory(nthreads: Int): String = withContext(scope.coroutineContext) {
        WhisperLib.benchMemcpy(nthreads)
    }

    /** 行列積速度ベンチ */
    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(scope.coroutineContext) {
        WhisperLib.benchGgmlMulMat(nthreads)
    }

    /** ネイティブリソース解放 */
    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0L
            Log.d(LOG_TAG, "WhisperContext: released native resources")
        }
        scope.cancel()
        dispatcher.close()
    }

    /** AutoCloseable 実装 → try-with-resources / use {} で利用可能 */
    override fun close() {
        runBlocking { release() }
    }

    companion object {
        /** ファイルパスからモデルをロード */
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            require(ptr != 0L) { "Couldn't create context from file: $filePath" }
            return WhisperContext(ptr)
        }

        /** InputStream からモデルをロード */
        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            require(ptr != 0L) { "Couldn't create context from input stream" }
            return WhisperContext(ptr)
        }

        /** assets 内のモデルをロード */
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            require(ptr != 0L) { "Couldn't create context from asset: $assetPath" }
            return WhisperContext(ptr)
        }

        /** ビルド環境/ライブラリ情報を取得 */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

/* ======================================================================
   Utility
   ====================================================================== */
private fun isArmEabiV7a(): Boolean =
    Build.SUPPORTED_ABIS.getOrNull(0) == "armeabi-v7a"

private fun isArmEabiV8a(): Boolean =
    Build.SUPPORTED_ABIS.getOrNull(0) == "arm64-v8a"

private fun readCpuInfo(): String? = try {
    File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
} catch (e: Exception) {
    Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
    null
}

/** 10ms frame → hh:mm:ss.SSS に変換 */
fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}
