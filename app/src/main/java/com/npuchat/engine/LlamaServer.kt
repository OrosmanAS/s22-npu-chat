package com.npuchat.engine

import android.content.Context
import com.npuchat.log.AppLog
import com.npuchat.model.ModelStore
import com.npuchat.model.ModelVariant
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Runs the bundled llama.cpp server (shipped as lib/arm64-v8a/libllama_server.so so the installer
 * extracts it executable into nativeLibraryDir) as a child process on 127.0.0.1, and talks to it
 * over its OpenAI-compatible HTTP API. Used for CPU models (MoE GGUFs, mmap'd from storage).
 */
object LlamaServer {
    private const val TAG = "Llama"
    private const val PORT = 18080
    const val BINARY = "libllama_server.so"

    @Volatile private var process: Process? = null
    @Volatile private var activeConn: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)

    val isRunning: Boolean get() = process?.isAlive == true

    /** Arguments shared by all CPU models. See ModelVariant.serverArgs for per-model ones. */
    fun args(context: Context, v: ModelVariant): List<String> = listOf(
        binary(context).absolutePath,
        "-m", ModelStore.file(context, v).absolutePath,
        "--host", "127.0.0.1", "--port", PORT.toString(),
        "--alias", v.name.lowercase(),
        "--jinja", "--reasoning-format", "deepseek",
        "--no-webui", "--no-warmup",
        // Weights stay mmap'd: no repacking into RAM copies, no extra prompt cache in RAM, and no
        // auto-fitting (the model is meant to be bigger than free RAM).
        "--load-mode", "mmap", "--no-repack", "--cache-ram", "0", "--fit", "off",
        "--parallel", "1",
    ) + v.serverArgs

    /** Starts the server; [onLine] receives each output line (also logged). */
    fun start(context: Context, v: ModelVariant, onLine: (String) -> Unit = {}) {
        stop()
        val cmd = args(context, v)
        AppLog.i(TAG, "exec: ${cmd.joinToString(" ")}")
        val p = ProcessBuilder(cmd)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()
        process = p
        thread(name = "llama-server-log", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    AppLog.d(TAG, line)
                    onLine(line)
                }
            } catch (_: IOException) {
            }
            val code = runCatching { p.waitFor() }.getOrNull()
            AppLog.w(TAG, "llama-server exited with code $code")
        }
    }

    fun stop() {
        process?.let {
            AppLog.i(TAG, "stopping llama-server")
            it.destroy()
            if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
        process = null
    }

    /**
     * Kills llama-server processes left by an earlier app process (e.g. after a restart, where only
     * the app's own pid was killed). Same uid, so we may see and signal them.
     */
    fun killStale() {
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().forEach { dir ->
            val cmd = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (cmd.contains(BINARY) && dir.name.toInt() != android.os.Process.myPid()) {
                AppLog.w(TAG, "killing stale llama-server pid ${dir.name}")
                android.os.Process.killProcess(dir.name.toInt())
            }
        }
    }

    fun exitCode(): Int? = process?.takeIf { !it.isAlive }?.exitValue()

    private var lastHealthError: String? = null

    /** GET /health: 200 = ready, 503 = still loading, null = not reachable. */
    fun health(): Int? = try {
        val c = (URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1000
            readTimeout = 2000
        }
        try { c.responseCode } finally { c.disconnect() }
    } catch (e: IOException) {
        // Log each distinct failure once: "connection refused" while starting is normal, anything
        // else (e.g. cleartext blocked by network security config) is a bug worth seeing.
        val msg = "${e.javaClass.simpleName}: ${e.message}"
        if (msg != lastHealthError) {
            lastHealthError = msg
            AppLog.d(TAG, "health check failed: $msg")
        }
        null
    }

    data class Delta(val content: String, val reasoning: String)
    data class Final(
        val promptTokens: Int?, val promptTps: Double?, val predictedTokens: Int?, val predictedTps: Double?,
        val contextUsed: Int?, val finishReason: String?,
    )

    /**
     * Streams a chat completion. The full history is sent every turn; llama-server reuses the KV
     * cache for the unchanged prefix, so only new tokens are processed.
     */
    fun chat(
        messages: List<Pair<String, String>>, // (role, content)
        v: ModelVariant,
        thinking: Boolean,
        maxTokens: Int,
        repeatPenalty: Float,
        onDelta: (Delta) -> Unit,
    ): Final {
        cancelled = false
        val body = JSONObject().apply {
            put("messages", JSONArray().apply {
                messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) }
            })
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
            put("temperature", v.temperature)
            put("repeat_penalty", repeatPenalty.toDouble())
            if (maxTokens > 0) put("max_tokens", maxTokens)
            put("chat_template_kwargs", JSONObject().put("enable_thinking", thinking))
            put("cache_prompt", true)
        }
        val conn = (URL("http://127.0.0.1:$PORT/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 0 // a token can take many seconds when weights page in from storage
            setRequestProperty("Content-Type", "application/json")
            setChunkedStreamingMode(0)
        }
        activeConn = conn
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("llama-server HTTP $code: ${err.take(500)}")
            }
            var final = Final(null, null, null, null, null, null)
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    val j = JSONObject(data)
                    j.optJSONArray("choices")?.optJSONObject(0)?.let { ch ->
                        ch.optJSONObject("delta")?.let { d ->
                            val c = d.optString("content", "").takeUnless { d.isNull("content") }.orEmpty()
                            val r = d.optString("reasoning_content", "").takeUnless { d.isNull("reasoning_content") }.orEmpty()
                            if (c.isNotEmpty() || r.isNotEmpty()) onDelta(Delta(c, r))
                        }
                        if (!ch.isNull("finish_reason")) final = final.copy(finishReason = ch.optString("finish_reason"))
                    }
                    j.optJSONObject("timings")?.let { t ->
                        final = final.copy(
                            promptTokens = t.optInt("prompt_n"), promptTps = t.optDouble("prompt_per_second"),
                            predictedTokens = t.optInt("predicted_n"), predictedTps = t.optDouble("predicted_per_second"),
                        )
                    }
                    j.optJSONObject("usage")?.let { u -> final = final.copy(contextUsed = u.optInt("total_tokens")) }
                }
            }
            return final
        } catch (e: IOException) {
            if (cancelled) throw java.util.concurrent.CancellationException("cancelled")
            throw e
        } finally {
            activeConn = null
            conn.disconnect()
        }
    }

    /** Dropping the connection makes llama-server stop generating. */
    fun cancel() {
        cancelled = true
        activeConn?.disconnect()
    }
}
