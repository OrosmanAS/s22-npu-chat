package com.npuchat.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.npuchat.ChatMode
import com.npuchat.NpuChatApp
import com.npuchat.RestartActivity
import com.npuchat.SettingsState
import com.npuchat.engine.EngineState
import com.npuchat.engine.GenEvent
import com.npuchat.engine.GenStats
import com.npuchat.engine.LlmEngine
import com.npuchat.engine.SysSnapshot
import com.npuchat.engine.SystemStats
import com.npuchat.log.AppLog
import com.npuchat.log.Diagnostics
import com.npuchat.model.DownloadWorker
import com.npuchat.model.ModelStore
import com.npuchat.model.ModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val thought: String = "",
    val streaming: Boolean = false,
    val stats: GenStats? = null,
    val error: String? = null,
    /** A divider from the app (e.g. "new context"), not part of the conversation. */
    val notice: Boolean = false,
)

data class Transfer(
    val phase: String,
    val done: Long,
    val total: Long,
    val error: String? = null,
    val variant: ModelVariant? = null,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val settingsStore = (app as NpuChatApp).settings
    val settings: StateFlow<SettingsState> = settingsStore.state
    val engine = LlmEngine.ui
    val lastInitCrashed = (app as NpuChatApp).lastInitCrashed

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating

    /** Bumped whenever model files change, so file-based state is re-read. */
    private val _modelRevision = MutableStateFlow(0)
    val modelRevision: StateFlow<Int> = _modelRevision
    val modelReady: StateFlow<Boolean> = _modelRevision.map { isModelReady() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, isModelReady())

    private val _importing = MutableStateFlow<Transfer?>(null)
    val importing: StateFlow<Transfer?> = _importing

    val download: StateFlow<Transfer?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(DownloadWorker.WORK_NAME)
        .map { infos -> infos.firstOrNull()?.let { toTransfer(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _sys = MutableStateFlow<SysSnapshot?>(null)
    /** System readings: every second while generating, every 3 s otherwise. */
    val sys: StateFlow<SysSnapshot?> = _sys

    private var nextId = 1L

    init {
        val s = settings.value
        if (isModelReady() && s.autoInit && !lastInitCrashed) {
            AppLog.i(TAG, "auto-init on launch")
            initEngine()
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                _sys.value = SystemStats.sample(ctx.filesDir)
                delay(if (_generating.value) 1000 else 3000)
            }
        }
    }

    private fun isModelReady() = ModelStore.isReady(ctx, settings.value.variant)

    private fun toTransfer(info: WorkInfo): Transfer? {
        val v = info.tags.firstNotNullOfOrNull { t -> ModelVariant.entries.firstOrNull { it.name == t } }
        return when (info.state) {
            WorkInfo.State.SUCCEEDED -> {
                _modelRevision.update { it + 1 }
                maybeAutoInit()
                null
            }
            WorkInfo.State.FAILED -> Transfer("failed", 0, 0, info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed", v)
            WorkInfo.State.CANCELLED -> null
            WorkInfo.State.ENQUEUED -> Transfer(if (info.runAttemptCount > 0) "waiting to resume" else "queued", 0, v?.sizeBytes ?: 0, variant = v)
            else -> Transfer(
                info.progress.getString(DownloadWorker.KEY_PHASE) ?: "starting",
                info.progress.getLong(DownloadWorker.KEY_DONE, 0),
                info.progress.getLong(DownloadWorker.KEY_TOTAL, v?.sizeBytes ?: 0),
                variant = v,
            )
        }
    }

    private fun maybeAutoInit() {
        if (isModelReady() && settings.value.autoInit && !lastInitCrashed && engine.value.state == EngineState.IDLE) initEngine()
    }

    // ---- models ----

    /** Downloads [v]. If no usable model is active yet, [v] becomes the active one. */
    fun downloadModel(v: ModelVariant) {
        if (!isModelReady()) settingsStore.update { it.copy(variant = v) }
        DownloadWorker.start(ctx, v)
    }

    fun cancelDownload() = DownloadWorker.cancel(ctx)

    fun deleteModel(v: ModelVariant) {
        ModelStore.delete(ctx, v)
        _modelRevision.update { it + 1 }
    }

    /**
     * Makes [v] the active model. One NPU model per process: if an engine is already loaded, the
     * app restarts to load the new one.
     */
    fun useModel(v: ModelVariant) {
        if (v == settings.value.variant) return
        AppLog.i(TAG, "switching model to ${v.name}")
        settingsStore.update { it.copy(variant = v) }
        _modelRevision.update { it + 1 }
        if (engine.value.state != EngineState.IDLE) RestartActivity.restartApp(ctx) else maybeAutoInit()
    }

    /** Copies a user-picked .litertlm into app storage, hashing on the way, and checks it. */
    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val cr = ctx.contentResolver
            val (name, size) = cr.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst()
                c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) to c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE))
            } ?: ("?" to 0L)
            AppLog.i(TAG, "import from $uri name=$name size=$size")
            val target = ModelVariant.entries.firstOrNull { it.fileName == name || it.sizeBytes == size } ?: settings.value.variant
            val part = ModelStore.partFile(ctx, target)
            try {
                _importing.value = Transfer("copying", 0, size)
                val md = MessageDigest.getInstance("SHA-256")
                cr.openInputStream(uri)!!.use { input ->
                    DigestOutputStream(part.outputStream(), md).use { out ->
                        val buf = ByteArray(1 shl 20)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 16L shl 20) { last = done; _importing.value = Transfer("copying", done, size) }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                val known = ModelVariant.bySha(sha)
                if (known == null) {
                    part.delete()
                    AppLog.e(TAG, "import: unknown SHA-256 $sha")
                    _importing.value = Transfer("failed", 0, 0, "SHA-256 does not match any known SM8450 model ($sha)")
                    return@launch
                }
                val dest = ModelStore.file(ctx, known)
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("rename failed")
                ModelStore.markVerified(ctx, known)
                AppLog.i(TAG, "import OK: ${known.name} verified")
                if (!isModelReady()) settingsStore.update { it.copy(variant = known) }
                _importing.value = null
                _modelRevision.update { it + 1 }
                withContext(Dispatchers.Main) { maybeAutoInit() }
            } catch (e: Exception) {
                AppLog.e(TAG, "import failed", e)
                part.delete()
                _importing.value = Transfer("failed", 0, 0, "Import failed: ${e.message}")
            }
        }
    }

    fun dismissImportError() { _importing.value = null }

    // ---- engine ----

    fun initEngine() {
        viewModelScope.launch { LlmEngine.initialize(ctx, settingsStore) }
    }

    fun updateSettings(transform: (SettingsState) -> SettingsState) {
        val before = settings.value
        settingsStore.update(transform)
        val after = settings.value
        AppLog.minLevel = after.logLevel
        if (before != after) AppLog.d(TAG, "settings changed: $after")
        if (LlmEngine.effectiveMode(before) != LlmEngine.effectiveMode(after) && LlmEngine.isReady) newChat()
        if (before.variant != after.variant) _modelRevision.update { it + 1 }
    }

    // ---- chat ----

    fun newChat() {
        stop()
        _messages.value = emptyList()
        viewModelScope.launch { LlmEngine.reset(settings.value) }
    }

    fun stop() {
        if (_generating.value) LlmEngine.cancel()
    }

    private fun addNotice(text: String) {
        _messages.update { it + ChatMessage(nextId++, fromUser = false, text = text, notice = true) }
    }

    /**
     * LiteRT-LM keeps the conversation's KV cache in step with the rendered chat history. A reply
     * that was cut off (stop, loop, error, full context) leaves them out of step, and every later
     * message then fails with "rendered template string does not start with the previous". So
     * after such a turn we start a fresh context.
     */
    private suspend fun freshContext(reason: String) {
        AppLog.i(TAG, "fresh context: $reason")
        LlmEngine.reset(settings.value)
        addNotice("New context started: $reason. Earlier messages are no longer remembered.")
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _generating.value || !LlmEngine.isReady) return
        _generating.value = true
        viewModelScope.launch {
            try {
                runTurn(prompt, retried = false)
            } finally {
                _generating.value = false
            }
        }
    }

    private suspend fun runTurn(prompt: String, retried: Boolean) {
        val cfg = settings.value
        val mode = LlmEngine.effectiveMode(cfg)
        // Raw mode re-renders the history each turn: use the exchanges since the last context reset.
        val sinceReset = _messages.value.takeLastWhile { !it.notice }
        val history = sinceReset.zipWithNext()
            .filter { (u, a) -> u.fromUser && !a.fromUser && a.error == null && a.stats?.stopReason == null }
            .map { (u, a) -> u.text to a.text }
        if (!retried) _messages.update { it + ChatMessage(nextId++, fromUser = true, text = prompt) }
        val reply = ChatMessage(nextId++, fromUser = false, text = "", streaming = true)
        _messages.update { it + reply }

        val flow = if (mode == ChatMode.CONVERSATION) LlmEngine.send(prompt, cfg) else LlmEngine.sendRaw(history, prompt, cfg)
        var outcome: String? = null // reason to start a fresh context after this turn
        try {
            flow.collect { ev ->
                when (ev) {
                    is GenEvent.Delta -> updateReply(reply.id) { it.copy(text = ev.text, thought = ev.thought, stats = ev.stats) }
                    is GenEvent.Done -> {
                        updateReply(reply.id) { it.copy(streaming = false, stats = ev.stats) }
                        val used = ev.stats.contextUsed
                        outcome = when {
                            ev.stats.stopReason == "loop" -> "the reply got stuck repeating itself and was stopped"
                            ev.stats.stopReason == "limit" -> "the reply hit the max output length"
                            used != null && used > engine.value.contextTokens - 512 -> "the context window is nearly full"
                            else -> null
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            AppLog.i(TAG, "generation stopped by user")
            updateReply(reply.id) { it.copy(streaming = false, error = if (it.text.isEmpty() && it.thought.isEmpty()) "Stopped" else null) }
            outcome = "the last reply was stopped"
        } catch (e: Throwable) {
            val desync = e.message?.contains("does not start with the previous rendered template") == true
            if (desync && !retried && mode == ChatMode.CONVERSATION) {
                AppLog.w(TAG, "conversation out of sync with its KV cache; resetting and retrying once")
                _messages.update { list -> list.filterNot { it.id == reply.id } }
                freshContext("the previous conversation state could not be continued")
                runTurn(prompt, retried = true)
                return
            }
            AppLog.e(TAG, "generation failed", e)
            updateReply(reply.id) { it.copy(streaming = false, error = e.message ?: e.toString()) }
            outcome = "the last reply failed"
        } finally {
            updateReply(reply.id) { it.copy(streaming = false) }
        }
        // Raw mode has no persistent KV cache, so it never needs a reset.
        outcome?.let { if (mode == ChatMode.CONVERSATION) freshContext(it) }
    }

    private fun updateReply(id: Long, f: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    // ---- logs ----

    suspend fun buildLogBundle(): File = withContext(Dispatchers.IO) {
        Diagnostics.buildBundle(ctx, LlmEngine.stepsReport())
    }

    private companion object {
        const val TAG = "UI"
    }
}
