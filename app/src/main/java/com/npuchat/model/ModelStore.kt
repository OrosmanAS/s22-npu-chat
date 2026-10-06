package com.npuchat.model

import android.content.Context
import com.npuchat.log.AppLog
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Chat format family. The raw-session path hand-builds prompts, so it only supports Qwen. */
enum class ModelFamily { QWEN, GRANITE, GEMMA }

private const val QWEN_REPO = "assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU"
private const val GRANITE_REPO = "assassinshadow/Granite-4.2-3B-SM8450-LiteRT-LM-NPU"
private const val GEMMA_REPO = "assassinshadow/Gemma-4-E2B-it-text-SM8450-LiteRT-LM-NPU"

/**
 * Every published LiteRT-LM bundle compiled for SM8450 / Hexagon v69 (all built against QAIRT
 * 2.50.0.260828 + LiteRT-LM v0.17.1, i.e. the runtime this app bundles). Bundles for other
 * Hexagon versions fail with "Failed to create QNN context: 1002".
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
    );

    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val summary: String get() = "ctx $contextTokens · $speed · %.2f GB".format(sizeBytes / 1e9)

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
