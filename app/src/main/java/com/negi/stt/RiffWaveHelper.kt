package com.negi.stt

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode a WAV file (linear PCM, 16-bit) into a FloatArray normalized to -1.0..+1.0.
 *
 * This function supports mono and stereo 16-bit PCM. If stereo, the output is the
 * average of left and right channels (i.e. downmixed to mono).
 *
 * Notes:
 *  - The function reads the entire file into memory; for very large files consider streaming.
 *  - The function validates RIFF/WAVE header and looks for "fmt " and "data" chunks.
 *  - Only PCM (audioFormat == 1) and bitsPerSample == 16 are accepted.
 *
 * @param file input WAV file
 * @return normalized float array (mono / averaged stereo)
 * @throws IllegalArgumentException for unsupported or malformed WAV files
 */
fun decodeWaveFile(file: File): FloatArray {
    val bytes = file.readBytes()
    if (bytes.size < 44) throw IllegalArgumentException("File too small to be a valid WAV")

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // RIFF header validation
    val riff = ByteArray(4)
    buffer.get(riff)
    if (String(riff, Charsets.US_ASCII) != "RIFF") throw IllegalArgumentException("Invalid RIFF header")

    // skip chunk size
    buffer.int

    val wave = ByteArray(4)
    buffer.get(wave)
    if (String(wave, Charsets.US_ASCII) != "WAVE") throw IllegalArgumentException("Invalid WAVE header")

    // Scan chunks for "fmt " and "data"
    var audioFormat: Int? = null
    var numChannels: Int? = null
    var sampleRate: Int? = null
    var bitsPerSample: Int? = null
    var dataOffset = -1
    var dataSize = -1

    // position is currently 12
    while (buffer.position() + 8 <= bytes.size) {
        val chunkIdBytes = ByteArray(4)
        buffer.get(chunkIdBytes)
        val chunkId = String(chunkIdBytes, Charsets.US_ASCII)
        val chunkSize = buffer.int

        when (chunkId) {
            "fmt " -> {
                // parse fmt chunk (we assume at least 16 bytes for PCM fmt)
                if (chunkSize < 16) throw IllegalArgumentException("Unexpected fmt chunk size: $chunkSize")
                audioFormat = buffer.short.toInt() and 0xFFFF
                numChannels = buffer.short.toInt() and 0xFFFF
                sampleRate = buffer.int
                // byteRate
                buffer.int
                // blockAlign
                buffer.short
                bitsPerSample = buffer.short.toInt() and 0xFFFF

                // If extra fmt bytes exist, skip them
                val remainingFmt = chunkSize - 16
                if (remainingFmt > 0) buffer.position(buffer.position() + remainingFmt)
            }
            "data" -> {
                // found data chunk
                dataOffset = buffer.position()
                dataSize = chunkSize
                // move position to end of data chunk (we'll read from dataOffset later)
                buffer.position(buffer.position() + chunkSize)
            }
            else -> {
                // skip other chunk
                buffer.position(buffer.position() + chunkSize)
            }
        }

        // chunks are word (2-byte) aligned; if chunkSize is odd, skip pad byte
        if ((chunkSize and 1) == 1 && buffer.position() < bytes.size) {
            buffer.position(buffer.position() + 1)
        }
        // continue scanning
    }

    if (audioFormat == null || numChannels == null || bitsPerSample == null || dataOffset < 0 || dataSize < 0) {
        throw IllegalArgumentException("Incomplete WAV header: missing fmt/data chunks")
    }

    if (audioFormat != 1) throw IllegalArgumentException("Unsupported WAV audio format (only PCM=1 supported), got=$audioFormat")
    if (bitsPerSample != 16) throw IllegalArgumentException("Unsupported bitsPerSample (only 16 supported), got=$bitsPerSample")
    if (numChannels !in 1..2) throw IllegalArgumentException("Unsupported channel count: $numChannels")

    // Read PCM samples from dataOffset
    val shortCount = dataSize / 2
    val samples = ShortArray(shortCount)
    val dataBuf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until shortCount) {
        samples[i] = dataBuf.short
    }

    // Convert to normalized floats, downmix stereo to mono if needed
    return if (numChannels == 1) {
        FloatArray(shortCount) { i ->
            (samples[i].toInt() / 32768.0f).coerceIn(-1f..1f)
        }
    } else {
        val outLen = shortCount / 2
        FloatArray(outLen) { i ->
            val l = samples[2 * i].toInt()
            val r = samples[2 * i + 1].toInt()
            ((l + r) / 2.0f / 32768.0f).coerceIn(-1f..1f)
        }
    }
}

/**
 * Encode PCM 16-bit data (mono) into a WAV file.
 *
 * This function writes a 44-byte standard WAV header (PCM, 16-bit, 16kHz, mono) and
 * then the raw PCM samples in little-endian order.
 *
 * @param file destination file
 * @param data PCM samples as ShortArray (mono)
 */
fun encodeWaveFile(file: File, data: ShortArray) {
    file.outputStream().use { out ->
        out.write(headerBytes(data.size * 2))
        val buffer = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(data)
        out.write(buffer.array())
    }
}

/**
 * Build a standard 44-byte WAV header for PCM 16-bit mono 16kHz.
 *
 * If you need different sample rates / channels, adjust this function.
 *
 * @param pcmDataBytes number of bytes in the PCM data section
 * @return 44-byte WAV header (little-endian)
 */
private fun headerBytes(pcmDataBytes: Int): ByteArray {
    require(pcmDataBytes >= 0) { "pcmDataBytes must be >= 0" }
    val totalFileSizeMinus8 = pcmDataBytes + 44 - 8
    val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

    bb.put("RIFF".toByteArray(Charsets.US_ASCII))
    bb.putInt(totalFileSizeMinus8)
    bb.put("WAVE".toByteArray(Charsets.US_ASCII))

    bb.put("fmt ".toByteArray(Charsets.US_ASCII))
    bb.putInt(16) // Subchunk1Size for PCM
    bb.putShort(1.toShort()) // AudioFormat = 1 (PCM)
    bb.putShort(1.toShort()) // NumChannels = 1 (mono)
    bb.putInt(16000) // SampleRate
    val byteRate = 16000 * 1 * 16 / 8
    bb.putInt(byteRate) // ByteRate
    bb.putShort((1 * 16 / 8).toShort()) // BlockAlign = NumChannels * BitsPerSample/8
    bb.putShort(16.toShort()) // BitsPerSample

    bb.put("data".toByteArray(Charsets.US_ASCII))
    bb.putInt(pcmDataBytes)

    return bb.array()
}

/*
日本語の説明（要点）
目的：元の実装をベースに、WAVヘッダーのパースを堅牢化して不正なファイルや非標準ヘッダ配置（fmt と data が順不同・拡張あり）にも対応できるようにしました。コメントは英語に統一しています。
主な改善点：
ヘッダ検証を追加（RIFF / WAVE のチェック）。
fmt と data をチャンク単位でスキャンして取得。fmt の拡張領域も対応。
PCM フォーマットチェック（PCMのみ、16bitのみ）を厳密に行う。
stereo の場合は L/R を平均してモノラル化して返す（既存仕様どおり）。
不正ファイル時は明確な IllegalArgumentException を投げるようにした。
注意事項 / 次の改善案：
現状はファイル全体をメモリに読み込む方式です。長時間録音や大きなファイルを扱う場合はストリーミング処理（ヘッダを先に読み、data 部をストリームで処理）へ変更することを推奨します。
encodeWaveFile は現在サンプルレート16000 / モノラル固定です。必要ならパラメータ化（sampleRate / channels / bitsPerSample）を追加できます。
WAVファイルが非常に大きい場合（>2GB）には RIFF の 32-bit サイズフィールドに収まらないため、RF64 等の拡張を検討してください。
エラー処理の振る舞い（例外 vs null / Result 型）をプロジェクト方針に合わせて変更しても良いです。
 */