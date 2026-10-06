package com.npuchat.model

import android.content.Context
import com.npuchat.log.AppLog
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Chat format family. The raw-session path hand-builds prompts, so it only supports Qwen. */
enum class ModelFamily { QWEN, GRANITE, GEMMA, LFM, KIMI }

/** Where a model runs: the Hexagon NPU via LiteRT-LM, or the CPU via a bundled llama-server. */
enum class ModelBackend(val label: String) { NPU("NPU"), CPU("CPU") }

private const val QWEN_REPO = "assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU"
private const val GRANITE_REPO = "assassinshadow/Granite-4.2-3B-SM8450-LiteRT-LM-NPU"
private const val GEMMA_REPO = "assassinshadow/Gemma-4-E2B-it-text-SM8450-LiteRT-LM-NPU"
private const val LFM_REPO = "LiquidAI/LFM2.5-8B-A1B-GGUF"
private const val KIMI_REPO = "bartowski/moonshotai_Kimi-Linear-48B-A3B-Instruct-GGUF"

/**
 * NPU models: every published LiteRT-LM bundle compiled for SM8450 / Hexagon v69 (all built
 * against QAIRT 2.50.0.260828 + LiteRT-LM v0.17.1, i.e. the runtime this app bundles). Bundles for
 * other Hexagon versions fail with "Failed to create QNN context: 1002".
 *
 * CPU models: MoE GGUFs run by llama-server with the weights mmap'd from storage, so a model can
 * be bigger than free RAM (slower the more it has to page in).
 *
 * Enum names are persisted in settings; don't rename them.
 */
enum class ModelVariant(
    val label: String,
    val family: ModelFamily,
    val repo: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val contextTokens: Int,
    val speed: String,
    val backend: ModelBackend = ModelBackend.NPU,
    /** Can freeze the phone (model far bigger than RAM). Never auto-loaded; asks before loading. */
    val experimental: Boolean = false,
    /** llama-server arguments for CPU models (besides model, host and port). */
    val serverArgs: List<String> = emptyList(),
    val temperature: Double = 0.7,
) {
    CTX_4K(
        "Qwen3.5-2B", ModelFamily.QWEN, QWEN_REPO,
        "qwen35-2b_sm8450.litertlm", 2_156_102_936L,
        "3dcd348bbabf7f6c103ad49050d69a7644f26a4f7ad9f03c7b1226633daf2a0f", 4096, "~16 tok/s",
    ),
    CTX_16K(
        "Qwen3.5-2B (long context)", ModelFamily.QWEN, QWEN_REPO,
        "qwen35-2b-ctx16384_sm8450.litertlm", 2_157_397_272L,
        "8b5bfbef1af4f215a28291eef8c5c97ee9ab0b5614481d3570e1f1570bae7ea9", 16384, "~8 tok/s",
    ),
    GEMMA4_E2B(
        "Gemma 4 E2B-it (text)", ModelFamily.GEMMA, GEMMA_REPO,
        "gemma4-e2b_sm8450.litertlm", 2_916_207_272L,
        "7872f34175f2a35895d1adb313578cbe04e6542d29c1b587ce343ad5b47223d9", 4096, "15–18 tok/s",
    ),
    GRANITE_4K(
        "Granite 4.2 3B", ModelFamily.GRANITE, GRANITE_REPO,
        "granite42-3b_sm8450.litertlm", 2_007_589_704L,
        "9461bd68ff988323f9af9cc7309de148da80b427be47272ac4995c17175d804c", 4096, "~8 tok/s",
    ),
    GRANITE_2K(
        "Granite 4.2 3B (fast)", ModelFamily.GRANITE, GRANITE_REPO,
        "granite42-3b-ctx2048_sm8450.litertlm", 2_005_680_968L,
        "6a0f7a3b0b40198df4d7807af3f74eee991fb3c6df0f54b80ef659e1912cfe0b", 2048, "~10 tok/s",
    ),
    LFM25_8B_A1B(
        "LFM2.5 8B-A1B (MoE, 1.5B active)", ModelFamily.LFM, LFM_REPO,
        "LFM2.5-8B-A1B-Q4_0.gguf", 4_844_678_368L,
        "48ed1465d761311b2fd57b7fb46cf969a20b3a8281945b04e10f52bd1609e715", 4096, "CPU, speed unknown",
        backend = ModelBackend.CPU,
        serverArgs = listOf("-c", "4096", "-t", "4", "-tb", "4"),
        temperature = 0.3,
    ),
    KIMI_LINEAR_48B(
        "Kimi-Linear 48B-A3B (MoE, 3B active)", ModelFamily.KIMI, KIMI_REPO,
        "moonshotai_Kimi-Linear-48B-A3B-Instruct-IQ2_XXS.gguf", 12_129_177_504L,
        "38150997ff3affffc1a94caa996336ca0ac2312d2d418a8261afdf30c8b13b78", 2048, "very slow",
        backend = ModelBackend.CPU,
        experimental = true,
        // Keep the working set small: 2 threads, small batches and context.
        serverArgs = listOf("-c", "2048", "-t", "2", "-tb", "2", "-b", "32", "-ub", "32"),
        temperature = 0.6,
    );

    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val summary: String get() = "${backend.label} · ctx $contextTokens · $speed · %.2f GB".format(sizeBytes / 1e9)

    companion object {
        fun bySha(sha: String) = entries.firstOrNull { it.sha256.equals(sha, ignoreCase = true) }
    }
}

/** Model files live in filesDir/models. A ".verified" marker records a passed SHA-256 check. */
object ModelStore {
    private const val TAG = "Model"

    fun dir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun file(context: Context, v: ModelVariant) = File(dir(context), v.fileName)
    fun partFile(context: Context, v: ModelVariant) = File(dir(context), v.fileName + ".part")
    private fun marker(context: Context, v: ModelVariant) = File(dir(context), v.fileName + ".verified")

    fun isReady(context: Context, v: ModelVariant): Boolean {
        val f = file(context, v)
        return f.exists() && f.length() == v.sizeBytes
    }

    fun isVerified(context: Context, v: ModelVariant) = isReady(context, v) && marker(context, v).exists()

    fun markVerified(context: Context, v: ModelVariant) {
        marker(context, v).writeText(v.sha256)
    }

    fun delete(context: Context, v: ModelVariant) {
        AppLog.i(TAG, "deleting ${v.fileName}")
        file(context, v).delete()
        partFile(context, v).delete()
        marker(context, v).delete()
    }

    /** SHA-256 of [input], reporting bytes read through [onProgress]. */
    fun sha256(input: InputStream, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var total = 0L
        var lastReport = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            total += n
            if (total - lastReport >= 32L shl 20) {
                onProgress(total)
                lastReport = total
            }
        }
        onProgress(total)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
