package com.npuchat.model

import android.content.Context
import com.npuchat.log.AppLog
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

private const val REPO = "https://huggingface.co/assassinshadow/Qwen3.5-2B-SM8450-LiteRT-LM-NPU/resolve/main"

enum class ModelVariant(
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val contextTokens: Int,
) {
    CTX_4K(
        "Qwen3.5-2B, context 4096 (~16 tok/s)",
        "qwen35-2b_sm8450.litertlm",
        2_156_102_936L,
        "3dcd348bbabf7f6c103ad49050d69a7644f26a4f7ad9f03c7b1226633daf2a0f",
        4096,
    ),
    CTX_16K(
        "Qwen3.5-2B, context 16384 (~8 tok/s)",
        "qwen35-2b-ctx16384_sm8450.litertlm",
        2_157_397_272L,
        "8b5bfbef1af4f215a28291eef8c5c97ee9ab0b5614481d3570e1f1570bae7ea9",
        16384,
    );

    val url: String get() = "$REPO/$fileName"

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
