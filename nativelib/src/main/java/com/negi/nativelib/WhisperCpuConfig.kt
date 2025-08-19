package com.negi.nativelib

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * 端末の高性能CPUコア数を自動判別し、AI推論で利用するスレッド数を最適化するユーティリティ。
 * - デフォルト: 高性能コア数以上、最低2スレッドを保証
 * - 失敗時も安全にFallback
 */
object WhisperCpuConfig {
    /**
     * 最適スレッド数（高性能CPUコア数, 最低2）
     */
    val preferredThreadCount: Int
        get() = CpuInfo.getHighPerfCpuCount().coerceAtLeast(2)
}

/**
 * CPUスペック取得・解析のための内部ユーティリティクラス。
 * - /proc/cpuinfo からコア・variant・クロック周波数などを読み取る
 * - 性能値の分布で「高性能コア数」を推定
 */
private class CpuInfo(private val lines: List<String>) {

    /**
     * 高性能CPUコア数を返す。周波数ベースで推定し、失敗時はvariantベースへフォールバック。
     */
    fun getHighPerfCpuCount(): Int = try {
        getHighPerfCpuCountByFrequencies()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Couldn't read CPU frequencies", e)
        getHighPerfCpuCountByVariant()
    }

    /**
     * 周波数情報に基づく「高性能コア数」推定。
     * - もっとも遅いコアは省く（省電力コア対策）。
     * - 各コアの最大周波数を取得し、最小値より高いものの数を数える。
     */
    private fun getHighPerfCpuCountByFrequencies(): Int =
        getCpuValues(property = "processor") { getMaxCpuFrequency(it.toInt()) }
            .also { Log.d(LOG_TAG, "Binned cpu frequencies (frequency, count): ${it.binnedValues()}") }
            .countDroppingMin()

    /**
     * CPU variant情報ベースで推定。
     * - 全コアのvariantを取得し、最小variant値の個数を返す。
     * - 構成によりvariant値が揃っている場合に安全なFallback。
     */
    private fun getHighPerfCpuCountByVariant(): Int =
        getCpuValues(property = "CPU variant") { it.substringAfter("0x").toInt(16) }
            .also { Log.d(LOG_TAG, "Binned cpu variants (variant, count): ${it.binnedValues()}") }
            .countKeepingMin()

    /**
     * コア値の分布をマップ（値→個数）で返す。デバッグ用。
     */
    private fun List<Int>.binnedValues() = groupingBy { it }.eachCount()

    /**
     * /proc/cpuinfo から各コアのproperty値を抽出し、mapperで整数値に変換してリスト化。
     */
    private fun getCpuValues(property: String, mapper: (String) -> Int) = lines
        .asSequence()
        .filter { it.startsWith(property) }
        .map { mapper(it.substringAfter(':').trim()) }
        .sorted()
        .toList()

    /**
     * リスト内の「最小値」より大きい値の数＝高性能コア数。
     * （例: [900, 900, 2200, 2200]→2）
     */
    private fun List<Int>.countDroppingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it > min }
    }

    /**
     * リスト内の「最小値」と同じ値の数を返す。
     */
    private fun List<Int>.countKeepingMin(): Int {
        val min = minOrNull() ?: return 0
        return count { it == min }
    }

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        /**
         * システム全体の高性能コア数を推定して返す。
         * - 失敗時はavailableProcessors-4、最低0を返す。
         */
        fun getHighPerfCpuCount(): Int = try {
            readCpuInfo().getHighPerfCpuCount()
        } catch (e: Exception) {
            Log.d(LOG_TAG, "Couldn't read CPU info", e)
            // 端末依存で安全な推定値：全コア数-4（省電力コア4個を除外）
            (Runtime.getRuntime().availableProcessors() - 4).coerceAtLeast(0)
        }

        /**
         * /proc/cpuinfo を全行読み込んでCpuInfoインスタンスを生成。
         */
        private fun readCpuInfo() = CpuInfo(
            BufferedReader(FileReader("/proc/cpuinfo"))
                .useLines { it.toList() }
        )

        /**
         * 指定コアの最大周波数(Hz)を取得（/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq）。
         * ※存在しない端末もあるので例外時は必ずcatchで吸収すること！
         */
        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu${cpuIndex}/cpufreq/cpuinfo_max_freq"
            return BufferedReader(FileReader(path)).use { it.readLine() }.toInt()
        }
    }
}
