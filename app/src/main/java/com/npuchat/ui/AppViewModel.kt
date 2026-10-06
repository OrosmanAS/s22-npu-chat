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
import com.npuchat.SettingsState
import com.npuchat.engine.EngineState
import com.npuchat.engine.GenEvent
import com.npuchat.engine.GenStats
import com.npuchat.engine.LlmEngine
import com.npuchat.log.AppLog
import com.npuchat.log.Diagnostics
import com.npuchat.model.DownloadWorker
import com.npuchat.model.ModelStore
import com.npuchat.model.ModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
)

data class Transfer(val phase: String, val done: Long, val total: Long, val error: String? = null) {
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

    private val _modelRevision = MutableStateFlow(0)
    val modelReady: StateFlow<Boolean> = _modelRevision.map { isModelReady() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, isModelReady())

    private val _importing = MutableStateFlow<Transfer?>(null)
    val importing: StateFlow<Transfer?> = _importing

    val download: StateFlow<Transfer?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(DownloadWorker.WORK_NAME)
        .map { infos -> infos.firstOrNull()?.let { toTransfer(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var genJob: Job? = null
    private var nextId = 1L

    init {
        val s = settings.value
        if (isModelReady() && s.autoInit && !lastInitCrashed) {
            AppLog.i(TAG, "auto-init on launch")
            initEngine()
        }
    }

    private fun isModelReady() = ModelStore.isReady(ctx, settings.value.variant)

    private fun toTransfer(info: WorkInfo): Transfer? {
        return when (info.state) {
            WorkInfo.State.SUCCEEDED -> {
                _modelRevision.update { it + 1 }
                maybeAutoInit()
                null
            }
            WorkInfo.State.FAILED -> Transfer("failed", 0, 0, info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "Download failed")
            WorkInfo.State.CANCELLED -> null
            WorkInfo.State.ENQUEUED -> Transfer(if (info.runAttemptCount > 0) "waiting to resume" else "queued", 0, settings.value.variant.sizeBytes)
            else -> Transfer(
                info.progress.getString(DownloadWorker.KEY_PHASE) ?: "starting",
                info.progress.getLong(DownloadWorker.KEY_DONE, 0),
                info.progress.getLong(DownloadWorker.KEY_TOTAL, settings.value.variant.sizeBytes),
            )
        }
    }

    private fun maybeAutoInit() {
        if (isModelReady() && settings.value.autoInit && !lastInitCrashed && engine.value.state == EngineState.IDLE) initEngine()
    }

    // ---- model ----

    fun startDownload() = DownloadWorker.start(ctx, settings.value.variant)
    fun cancelDownload() = DownloadWorker.cancel(ctx)

    fun deleteModel() {
        ModelStore.delete(ctx, settings.value.variant)
        _modelRevision.update { it + 1 }
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
                    _importing.value = Transfer("failed", 0, 0, "SHA-256 does not match either published model ($sha)")
                    return@launch
                }
                val dest = ModelStore.file(ctx, known)
                dest.delete()
                if (!part.renameTo(dest)) throw IOException("rename failed")
                ModelStore.markVerified(ctx, known)
                AppLog.i(TAG, "import OK: ${known.name} verified")
                if (known != settings.value.variant) settingsStore.update { it.copy(variant = known) }
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
        if (before.chatMode != after.chatMode && LlmEngine.isReady) newChat()
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

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _generating.value || !LlmEngine.isReady) return
        val cfg = settings.value
        // History for the raw path: completed exchanges only.
        val history = _messages.value.zipWithNext()
            .filter { (u, a) -> u.fromUser && !a.fromUser && a.error == null }
            .map { (u, a) -> u.text to a.text }
        val user = ChatMessage(nextId++, fromUser = true, text = prompt)
        val reply = ChatMessage(nextId++, fromUser = false, text = "", streaming = true)
        _messages.update { it + user + reply }
        _generating.value = true

        val flow = if (cfg.chatMode == ChatMode.CONVERSATION) LlmEngine.send(prompt, cfg) else LlmEngine.sendRaw(history, prompt, cfg)
        genJob = viewModelScope.launch {
            try {
                flow.collect { ev ->
                    when (ev) {
                        is GenEvent.Delta -> updateReply(reply.id) { it.copy(text = ev.text, thought = ev.thought, stats = ev.stats) }
                        is GenEvent.Done -> updateReply(reply.id) { it.copy(streaming = false, stats = ev.stats) }
                    }
                }
            } catch (e: CancellationException) {
                AppLog.i(TAG, "generation cancelled")
                updateReply(reply.id) { it.copy(streaming = false, error = if (it.text.isEmpty()) "Stopped" else null) }
            } catch (e: Throwable) {
                AppLog.e(TAG, "generation failed", e)
                updateReply(reply.id) { it.copy(streaming = false, error = e.message ?: e.toString()) }
            } finally {
                updateReply(reply.id) { it.copy(streaming = false) }
                _generating.value = false
            }
        }
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
