package com.npuchat.engine

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.NoRepeatNgramConfig
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.ThinkingConfig
import com.npuchat.ChatMode
import com.npuchat.Settings
import com.npuchat.SettingsState
import com.npuchat.log.AppLog
import com.npuchat.model.ModelBackend
import com.npuchat.model.ModelFamily
import com.npuchat.model.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

enum class StepStatus { PENDING, RUNNING, OK, WARN, FAIL, SKIPPED }

data class InitStep(
    val id: String,
    val title: String,
    val status: StepStatus = StepStatus.PENDING,
    val detail: String = "",
    val ms: Long? = null,
)

enum class EngineState { IDLE, INITIALIZING, READY, FAILED }

data class EngineUi(
    val state: EngineState = EngineState.IDLE,
    val steps: List<InitStep> = defaultSteps(),
    val error: String? = null,
    val initMs: Long? = null,
    val modelName: String? = null,
    val contextTokens: Int = 0,
    val backend: ModelBackend = ModelBackend.NPU,
)

private fun defaultSteps() = listOf(
    InitStep("device", "Device / SoC"),
    InitStep("env", "DSP environment variables"),
    InitStep("libs", "Bundled native libraries"),
    InitStep("fastrpc", "FastRPC (libcdsprpc.so)"),
    InitStep("dlopen", "Load QNN + dispatch libs"),
    InitStep("model", "Model file"),
    InitStep("memory", "Free memory"),
    InitStep("engine", "LiteRT-LM engine on NPU"),
    InitStep("conversation", "Conversation"),
)

private fun cpuSteps() = listOf(
    InitStep("device", "Device / SoC"),
    InitStep("binary", "llama.cpp server binary"),
    InitStep("model", "Model file"),
    InitStep("memory", "Free memory"),
    InitStep("server", "Start llama-server"),
    InitStep("load", "Load model (mmap from storage)"),
)

data class GenStats(
    val chunks: Int,
    val elapsedMs: Long,
    val ttftMs: Long?,
    val decodeTps: Double? = null,
    val prefillTps: Double? = null,
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
    val contextUsed: Int? = null,
    /** Why generation ended early ("loop" / "limit"), or null for a normal end or user stop. */
    val stopReason: String? = null,
) {
    /** Live estimate: one streamed chunk is about one token. */
    val liveTps: Double?
        get() {
            val t = ttftMs ?: return null
            val genMs = elapsedMs - t
            return if (chunks > 1 && genMs > 0) (chunks - 1) * 1000.0 / genMs else null
        }
}

sealed class GenEvent {
    data class Delta(val text: String, val thought: String, val stats: GenStats) : GenEvent()
    data class Done(val stats: GenStats) : GenEvent()
}

/**
 * Owns the single LiteRT-LM engine for this process. All native calls run on one dedicated
 * thread. The NPU can only be initialised once per process; after a failure the app must be
 * restarted (see RestartActivity).
 */
object LlmEngine {
    private const val TAG = "Engine"
    private const val CHAT = "Chat"

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-engine").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _ui = MutableStateFlow(EngineUi())
    val ui: StateFlow<EngineUi> = _ui

    val isReady get() = _ui.value.state == EngineState.READY

    /**
     * The raw-session path hand-builds Qwen prompts, so other NPU families always use the
     * Conversation API. CPU models are stateless like raw mode: the history is sent every turn.
     */
    fun effectiveMode(cfg: SettingsState): ChatMode = when {
        cfg.variant.backend == ModelBackend.CPU -> ChatMode.RAW_SESSION
        cfg.variant.family == ModelFamily.QWEN -> cfg.chatMode
        else -> ChatMode.CONVERSATION
    }

    private fun step(id: String, status: StepStatus, detail: String = "", ms: Long? = null) {
        _ui.update { s -> s.copy(steps = s.steps.map { if (it.id == id) it.copy(status = status, detail = detail, ms = ms) else it }) }
        val title = _ui.value.steps.first { it.id == id }.title
        val line = "[step $id] $status: $title${if (ms != null) " (${ms} ms)" else ""}${if (detail.isNotEmpty()) "\n$detail" else ""}"
        when (status) {
            StepStatus.FAIL -> AppLog.e(TAG, line)
            StepStatus.WARN -> AppLog.w(TAG, line)
            else -> AppLog.i(TAG, line)
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    fun stepsReport(): String = _ui.value.let { s ->
        "state=${s.state} error=${s.error ?: "-"} initMs=${s.initMs ?: "-"}\n" +
            s.steps.joinToString("\n") { "${it.status} ${it.id}${it.ms?.let { m -> " ${m}ms" } ?: ""}: ${it.detail.replace("\n", "\n    ")}" }
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initialize(context: Context, settings: Settings): Boolean = withContext(dispatcher) {
        if (_ui.value.state == EngineState.READY || _ui.value.state == EngineState.INITIALIZING) return@withContext isReady
        val cfg = settings.current
        val variant = cfg.variant
        if (variant.backend == ModelBackend.CPU) return@withContext initCpu(context, settings)
        val tStart = System.nanoTime()
        _ui.value = EngineUi(state = EngineState.INITIALIZING, modelName = variant.fileName)
        AppLog.i(TAG, "==== NPU init start: model=${variant.fileName} mode=${cfg.chatMode} preload=${cfg.preloadLibs} ====")

        fun fail(id: String, msg: String, ms: Long? = null): Boolean {
            step(id, StepStatus.FAIL, msg, ms)
            _ui.update { s ->
                s.copy(
                    state = EngineState.FAILED,
                    error = msg.lineSequence().first(),
                    steps = s.steps.map { if (it.status == StepStatus.PENDING) it.copy(status = StepStatus.SKIPPED) else it },
                )
            }
            AppLog.e(TAG, "==== NPU init FAILED at $id ====")
            return false
        }

        // 1. Device
        step("device", StepStatus.RUNNING)
        val dev = NpuEnv.deviceReport(context)
        step("device", if (NpuEnv.isSm8450()) StepStatus.OK else StepStatus.WARN,
            dev + if (NpuEnv.isSm8450()) "" else "\nNot SM8450: the model's QNN contexts are compiled for Hexagon v69 only.")

        // 2. Environment (set in Application.onCreate, verified here)
        val libDir = NpuEnv.nativeLibDir(context)
        val env = NpuEnv.envReport()
        val adspOk = android.system.Os.getenv("ADSP_LIBRARY_PATH")?.startsWith(libDir) == true
        if (!adspOk) return@withContext fail("env", "ADSP_LIBRARY_PATH does not start with nativeLibraryDir ($libDir)\n$env")
        step("env", StepStatus.OK, env)

        // 3. Bundled libs
        val libs = NpuEnv.checkLibs(context)
        val libText = libs.joinToString("\n") { "${if (it.present) "ok " else "MISSING "}${it.name} ${if (it.present) it.size else ""}" } +
            "\n\nBuild manifest:\n" + NpuEnv.nativeManifest(context)
        AppLog.d(TAG, NpuEnv.listNativeLibDir(context))
        if (libs.any { !it.present }) return@withContext fail("libs", libText)
        step("libs", StepStatus.OK, libText)

        // 4. FastRPC
        val vendor = NpuEnv.vendorReport()
        val rpcErr = NpuEnv.tryLoad("libcdsprpc.so (vendor)") { System.loadLibrary("cdsprpc") }
        step("fastrpc", if (rpcErr == null) StepStatus.OK else StepStatus.WARN,
            (if (rpcErr == null) "System.loadLibrary(\"cdsprpc\") OK" else "System.loadLibrary(\"cdsprpc\") failed: $rpcErr") + "\n" + vendor)

        // 5. dlopen our libs (evidence: any missing symbol / linker namespace problem shows up here)
        if (cfg.preloadLibs) {
            step("dlopen", StepStatus.RUNNING)
            val results = mutableListOf<String>()
            for (name in listOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpV69Stub.so", "libLiteRtDispatch_Qualcomm.so")) {
                val err = NpuEnv.tryLoad(name) { System.load(File(libDir, name).absolutePath) }
                results += if (err == null) "ok $name" else "FAILED $name: $err"
                if (err != null) return@withContext fail("dlopen", results.joinToString("\n"))
            }
            // Touching the JNI class loads liblitertlm_jni.so.
            val (jniErr, jniMs) = timed {
                NpuEnv.tryLoad("liblitertlm_jni.so") {
                    Engine.setNativeMinLogSeverity(if (cfg.nativeVerbose) LogSeverity.VERBOSE else LogSeverity.INFO)
                }
            }
            results += if (jniErr == null) "ok liblitertlm_jni.so ($jniMs ms)" else "FAILED liblitertlm_jni.so: $jniErr"
            if (jniErr != null) return@withContext fail("dlopen", results.joinToString("\n"))
            step("dlopen", StepStatus.OK, results.joinToString("\n"))
        } else {
            Engine.setNativeMinLogSeverity(if (cfg.nativeVerbose) LogSeverity.VERBOSE else LogSeverity.INFO)
            step("dlopen", StepStatus.SKIPPED, "Preload disabled in settings; the runtime loads them itself.")
        }

        // 6. Model
        val modelFile = ModelStore.file(context, variant)
        if (!ModelStore.isReady(context, variant)) {
            return@withContext fail("model", "${modelFile.absolutePath} missing or wrong size (${modelFile.length()} != ${variant.sizeBytes})")
        }
        val verified = ModelStore.isVerified(context, variant)
        step("model", if (verified) StepStatus.OK else StepStatus.WARN,
            "${modelFile.absolutePath}\n${modelFile.length()} bytes, SHA-256 ${if (verified) "verified" else "NOT verified"}")

        // 7. Memory
        val mem = NpuEnv.memoryReport(context)
        step("memory", StepStatus.OK, mem + "\nThe model maps ~1.85 GB on the cDSP; close heavy apps if init fails.")

        // 8. Engine
        step("engine", StepStatus.RUNNING, "Creating QNN contexts (9 of them); this takes a while…")
        ExperimentalFlags.enableBenchmark = cfg.benchmark
        val engineConfig = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.NPU(nativeLibraryDir = libDir),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = null,
            cacheDir = context.cacheDir.absolutePath,
        )
        AppLog.i(TAG, "EngineConfig: $engineConfig benchmark=${cfg.benchmark}")
        settings.initInProgress = true
        val (eng, engMs) = timed {
            try {
                Engine(engineConfig).also { it.initialize() }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Engine.initialize() threw", t)
                settings.initInProgress = false
                return@withContext fail("engine", "${t.javaClass.simpleName}: ${t.message}\n\nCommon causes: 1002 = DSP could not load the skel / map contexts; " +
                    "\"unresolved custom op: DISPATCH_OP\" = dispatch lib not found or ABI mismatch. See Logs.")
            }
        }
        settings.initInProgress = false
        engine = eng
        step("engine", StepStatus.OK, "initialize() returned in $engMs ms\nCPU affinity: ${NpuEnv.cpusAllowed()}\n${NpuEnv.memoryReport(context)}", engMs)

        // 9. Conversation
        if (effectiveMode(cfg) == ChatMode.CONVERSATION) {
            val (err, convMs) = timed { runCatching { openConversation(cfg) }.exceptionOrNull() }
            if (err != null) {
                AppLog.e(TAG, "createConversation threw", err)
                return@withContext fail("conversation", "${err.javaClass.simpleName}: ${err.message}", convMs)
            }
            step("conversation", StepStatus.OK, "created (samplerConfig=null, constrained decoding off)", convMs)
        } else {
            step("conversation", StepStatus.SKIPPED, "Raw session mode: a session is created per message.")
        }

        val total = (System.nanoTime() - tStart) / 1_000_000
        _ui.update { it.copy(state = EngineState.READY, initMs = total, contextTokens = variant.contextTokens) }
        AppLog.i(TAG, "==== NPU init OK in $total ms ====")
        true
    }

    private fun failInit(id: String, msg: String, ms: Long? = null): Boolean {
        step(id, StepStatus.FAIL, msg, ms)
        _ui.update { s ->
            s.copy(
                state = EngineState.FAILED,
                error = msg.lineSequence().first(),
                steps = s.steps.map { if (it.status == StepStatus.PENDING) it.copy(status = StepStatus.SKIPPED) else it },
            )
        }
        AppLog.e(TAG, "==== init FAILED at $id ====")
        return false
    }

    /** CPU models: start the bundled llama-server and wait until it has the model loaded. */
    private fun initCpu(context: Context, settings: Settings): Boolean {
        val cfg = settings.current
        val v = cfg.variant
        val tStart = System.nanoTime()
        _ui.value = EngineUi(state = EngineState.INITIALIZING, steps = cpuSteps(), modelName = v.fileName, backend = ModelBackend.CPU)
        AppLog.i(TAG, "==== CPU init start: model=${v.fileName} experimental=${v.experimental} ====")

        step("device", StepStatus.OK, NpuEnv.deviceReport(context))

        val bin = LlamaServer.binary(context)
        if (!bin.exists()) return failInit("binary", "${bin.absolutePath} is missing (APK built without llama-server?)")
        if (!bin.canExecute()) return failInit("binary", "${bin.absolutePath} is not executable")
        val version = NpuEnv.nativeManifest(context).lines().firstOrNull { it.startsWith("llama.cpp") }.orEmpty()
        step("binary", StepStatus.OK, "${bin.absolutePath}\n${bin.length()} bytes\n$version")

        val modelFile = ModelStore.file(context, v)
        if (!ModelStore.isReady(context, v)) {
            return failInit("model", "${modelFile.absolutePath} missing or wrong size (${modelFile.length()} != ${v.sizeBytes})")
        }
        val verified = ModelStore.isVerified(context, v)
        step("model", if (verified) StepStatus.OK else StepStatus.WARN,
            "${modelFile.absolutePath}\n${modelFile.length()} bytes, SHA-256 ${if (verified) "verified" else "NOT verified"}")

        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bigger = v.sizeBytes > mi.availMem
        step("memory", if (bigger) StepStatus.WARN else StepStatus.OK, NpuEnv.memoryReport(context) +
            if (bigger) "\nModel (%.1f GB) is bigger than free RAM: weights are paged in from storage as tokens need them.".format(v.sizeBytes / 1e9) else "")

        val tail = ArrayDeque<String>()
        settings.initInProgress = true
        val (startErr, startMs) = timed {
            runCatching {
                LlamaServer.start(context, v) { line -> synchronized(tail) { if (tail.size >= 20) tail.removeFirst(); tail.addLast(line) } }
            }.exceptionOrNull()
        }
        if (startErr != null) {
            settings.initInProgress = false
            AppLog.e(TAG, "llama-server start failed", startErr)
            return failInit("server", "${startErr.javaClass.simpleName}: ${startErr.message}", startMs)
        }
        step("server", StepStatus.OK, LlamaServer.args(context, v).drop(1).joinToString(" "), startMs)

        step("load", StepStatus.RUNNING, "Loading ${v.label}…")
        val t0 = System.nanoTime()
        val deadline = t0 + 20L * 60 * 1_000_000_000
        while (true) {
            val code = LlamaServer.health()
            if (code == 200) break
            if (!LlamaServer.isRunning || System.nanoTime() > deadline) {
                settings.initInProgress = false
                val last = synchronized(tail) { tail.joinToString("\n") }
                val wasRunning = LlamaServer.isRunning
                val exit = LlamaServer.exitCode()
                LlamaServer.stop() // don't leave a half-loaded server holding RAM
                return failInit("load",
                    (if (wasRunning) "Timed out waiting for the model to load" else "llama-server exited (code $exit)") +
                        "\n\nLast output:\n$last",
                    (System.nanoTime() - t0) / 1_000_000)
            }
            Thread.sleep(500)
        }
        settings.initInProgress = false
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        step("load", StepStatus.OK, "Ready. ${NpuEnv.memoryReport(context)}", loadMs)

        val total = (System.nanoTime() - tStart) / 1_000_000
        _ui.update { it.copy(state = EngineState.READY, initMs = total, contextTokens = v.contextTokens) }
        AppLog.i(TAG, "==== CPU init OK in $total ms ====")
        return true
    }

    private fun conversationConfig(cfg: SettingsState) = ConversationConfig(
        systemInstruction = cfg.systemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
        // NPU executor is greedy; a sampler config is ignored at best.
        samplerConfig = null,
    )

    private fun openConversation(cfg: SettingsState) {
        conversation?.let { runCatching { it.close() } }
        conversation = null
        val e = engine ?: error("engine not initialised")
        conversation = e.createConversation(conversationConfig(cfg))
        AppLog.i(CHAT, "conversation opened")
    }

    /** Starts a fresh chat (new KV cache). The engine and NPU contexts stay loaded. */
    suspend fun reset(cfg: SettingsState) = withContext(dispatcher) {
        if (engine == null) return@withContext // CPU models keep no state between requests
        if (effectiveMode(cfg) == ChatMode.CONVERSATION) {
            openConversation(cfg)
        } else {
            conversation?.let { runCatching { it.close() } }
            conversation = null
            AppLog.i(CHAT, "raw session mode: conversation closed")
        }
    }

    fun cancel() {
        AppLog.i(CHAT, "cancel requested")
        if (LlamaServer.isRunning) LlamaServer.cancel()
        runCatching { conversation?.cancelProcess() }.onFailure { AppLog.w(CHAT, "cancelProcess failed", it) }
        activeSession?.let { s -> runCatching { s.cancelProcess() }.onFailure { AppLog.w(CHAT, "session cancel failed", it) } }
    }

    // ---- Conversation API path ----

    @OptIn(ExperimentalApi::class)
    fun send(text: String, cfg: SettingsState): Flow<GenEvent> = flow {
        val conv = conversation ?: error("No conversation (engine not ready, or raw mode active)")
        AppLog.i(CHAT, "send (conversation) thinking=${cfg.thinking} chars=${text.length}")
        val t0 = System.nanoTime()
        var ttft: Long? = null
        var chunks = 0
        val answer = StringBuilder()
        val thought = StringBuilder()
        fun stats() = GenStats(chunks, (System.nanoTime() - t0) / 1_000_000, ttft)

        var stopReason: String? = null
        val rep = cfg.repetitionPenalty.takeIf { it > 1.0f }?.let {
            RepetitionPenaltyConfig(repetitionPenalty = it, presencePenalty = 0f, frequencyPenalty = 0f, windowSize = 512)
        }
        val ngram = cfg.noRepeatNgram.takeIf { it > 0 }?.let { NoRepeatNgramConfig(noRepeatNgramSize = it, windowSize = 2048) }
        val thinkingConfig = ThinkingConfig(
            enableThinking = cfg.thinking,
            thinkingTokenBudget = if (cfg.thinking && cfg.thinkingBudget > 0) cfg.thinkingBudget else -1,
        )
        AppLog.d(CHAT, "decode constraints: $rep $ngram $thinkingConfig maxOut=${cfg.maxOutputTokens}")
        try {
            conv.sendMessageAsync(
                Message.user(text),
                repetitionPenaltyConfig = rep,
                noRepeatNgramConfig = ngram,
                maxOutputToken = cfg.maxOutputTokens.takeIf { it > 0 },
                thinkingConfig = thinkingConfig,
            ).collect { m ->
                chunks++
                if (ttft == null) ttft = (System.nanoTime() - t0) / 1_000_000
                val delta = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                answer.append(delta)
                for ((_, v) in m.channels) thought.append(v)
                if (chunks <= 3) AppLog.d(CHAT, "chunk#$chunks text=${delta.take(40).quoted()} channels=${m.channels.mapValues { it.value.take(40) }}")
                else AppLog.v(CHAT, "chunk#$chunks ${delta.quoted()}")
                emit(GenEvent.Delta(answer.toString(), thought.toString(), stats()))
                if (stopReason == null && chunks % 8 == 0 &&
                    (LoopDetector.isLooping(thought) || LoopDetector.isLooping(answer))
                ) {
                    stopReason = "loop"
                    AppLog.w(CHAT, "repetition loop detected at chunk $chunks; cancelling")
                    runCatching { conv.cancelProcess() }
                }
            }
        } catch (e: CancellationException) {
            // Our own loop-stop cancels the native call; a real coroutine cancellation still propagates.
            if (stopReason == null || !currentCoroutineContext().isActive) throw e
        }

        var final = stats().copy(stopReason = stopReason)
        if (cfg.benchmark) runCatching { conv.getBenchmarkInfo() }
            .onSuccess { b ->
                AppLog.i(CHAT, "benchmark: $b")
                final = final.copy(
                    decodeTps = b.lastDecodeTokensPerSecond, prefillTps = b.lastPrefillTokensPerSecond,
                    prefillTokens = b.lastPrefillTokenCount, decodeTokens = b.lastDecodeTokenCount,
                )
            }
            .onFailure { AppLog.w(CHAT, "getBenchmarkInfo failed", it) }
        runCatching { conv.getTokenCount() }.onSuccess { final = final.copy(contextUsed = it) }
        AppLog.i(CHAT, "done: chunks=$chunks elapsed=${final.elapsedMs}ms ttft=${final.ttftMs}ms live=${final.liveTps?.let { "%.1f".format(it) }} tok/s ctx=${final.contextUsed}")
        emit(GenEvent.Done(final))
    }.flowOn(dispatcher)

    // ---- CPU path (llama-server over localhost) ----

    private sealed class CpuEvent {
        data class D(val delta: LlamaServer.Delta) : CpuEvent()
        data class F(val final: LlamaServer.Final) : CpuEvent()
    }

    fun sendCpu(history: List<Pair<String, String>>, text: String, cfg: SettingsState): Flow<GenEvent> = flow {
        val messages = buildList {
            if (cfg.systemPrompt.isNotBlank()) add("system" to cfg.systemPrompt.trim())
            for ((u, a) in history) { add("user" to u); add("assistant" to a) }
            add("user" to text)
        }
        AppLog.i(CHAT, "send (cpu) model=${cfg.variant.name} thinking=${cfg.thinking} turns=${history.size} chars=${text.length}")
        val t0 = System.nanoTime()
        var ttft: Long? = null
        var chunks = 0
        val answer = StringBuilder()
        val thought = StringBuilder()
        var final: LlamaServer.Final? = null
        var stopReason: String? = null
        fun stats() = GenStats(chunks, (System.nanoTime() - t0) / 1_000_000, ttft)

        val events = callbackFlow<CpuEvent> {
            val job = launch(Dispatchers.IO) {
                try {
                    val f = LlamaServer.chat(messages, cfg.variant, cfg.thinking, cfg.maxOutputTokens, cfg.repetitionPenalty) {
                        trySend(CpuEvent.D(it))
                    }
                    send(CpuEvent.F(f))
                    close()
                } catch (t: Throwable) {
                    close(t)
                }
            }
            awaitClose { if (job.isActive) LlamaServer.cancel() }
        }.buffer(Channel.UNLIMITED)

        try {
            events.collect { ev ->
                when (ev) {
                    is CpuEvent.D -> {
                        chunks++
                        if (ttft == null) ttft = (System.nanoTime() - t0) / 1_000_000
                        answer.append(ev.delta.content)
                        thought.append(ev.delta.reasoning)
                        if (chunks <= 3) AppLog.d(CHAT, "cpu chunk#$chunks ${ev.delta.content.take(40).quoted()} reasoning=${ev.delta.reasoning.take(40).quoted()}")
                        else AppLog.v(CHAT, "cpu chunk#$chunks ${ev.delta.content.quoted()}")
                        emit(GenEvent.Delta(answer.toString(), thought.toString(), stats()))
                        if (stopReason == null && chunks % 8 == 0 &&
                            (LoopDetector.isLooping(thought) || LoopDetector.isLooping(answer))
                        ) {
                            stopReason = "loop"
                            AppLog.w(CHAT, "repetition loop detected at chunk $chunks; cancelling")
                            LlamaServer.cancel()
                        }
                    }
                    is CpuEvent.F -> final = ev.final
                }
            }
        } catch (e: CancellationException) {
            if (stopReason == null || !currentCoroutineContext().isActive) throw e
        }

        val f = final
        if (stopReason == null && f?.finishReason == "length") stopReason = "limit"
        val done = stats().copy(
            decodeTps = f?.predictedTps, prefillTps = f?.promptTps,
            prefillTokens = f?.promptTokens, decodeTokens = f?.predictedTokens,
            contextUsed = f?.contextUsed, stopReason = stopReason,
        )
        AppLog.i(CHAT, "cpu done: chunks=$chunks elapsed=${done.elapsedMs}ms ttft=${done.ttftMs}ms decode=${f?.predictedTps} tok/s prefill=${f?.promptTps} tok/s (${f?.promptTokens} tok) ctx=${f?.contextUsed} finish=${f?.finishReason}")
        emit(GenEvent.Done(done))
    }.flowOn(Dispatchers.IO)

    // ---- Raw session path (hand-rendered Qwen prompt, as in the model card's CLI) ----

    @Volatile private var activeSession: Session? = null

    fun sendRaw(history: List<Pair<String, String>>, text: String, cfg: SettingsState): Flow<GenEvent> = flow {
        val e = engine ?: error("engine not initialised")
        val prompt = QwenPrompt.render(cfg.systemPrompt, history, text, cfg.thinking)
        AppLog.i(CHAT, "send (raw session) thinking=${cfg.thinking} promptChars=${prompt.length} turns=${history.size}")
        AppLog.v(CHAT, "raw prompt:\n$prompt")
        val t0 = System.nanoTime()
        var ttft: Long? = null
        var chunks = 0
        val raw = StringBuilder()
        var stopReason: String? = null
        try {
            rawStream(e, prompt).collect { piece ->
                chunks++
                if (ttft == null) ttft = (System.nanoTime() - t0) / 1_000_000
                raw.append(piece)
                if (chunks <= 3) AppLog.d(CHAT, "raw chunk#$chunks ${piece.take(40).quoted()}") else AppLog.v(CHAT, "raw chunk#$chunks ${piece.quoted()}")
                val parts = ThinkSplit.split(raw.toString(), startsInside = cfg.thinking)
                emit(GenEvent.Delta(parts.answer, parts.thought, GenStats(chunks, (System.nanoTime() - t0) / 1_000_000, ttft)))
                // Sessions take no decode constraints, so cap length and catch loops here.
                val limitHit = cfg.maxOutputTokens in 1..chunks
                if (stopReason == null && (limitHit || (chunks % 8 == 0 && LoopDetector.isLooping(raw)))) {
                    stopReason = if (limitHit) "limit" else "loop"
                    AppLog.w(CHAT, "raw: stopping ($stopReason) at chunk $chunks")
                    activeSession?.let { s -> runCatching { s.cancelProcess() } }
                }
            }
        } catch (ce: CancellationException) {
            if (stopReason == null || !currentCoroutineContext().isActive) throw ce
        }
        val final = GenStats(chunks, (System.nanoTime() - t0) / 1_000_000, ttft, stopReason = stopReason)
        AppLog.i(CHAT, "raw done: chunks=$chunks elapsed=${final.elapsedMs}ms ttft=${final.ttftMs}ms live=${final.liveTps?.let { "%.1f".format(it) }} tok/s")
        emit(GenEvent.Done(final))
    }.flowOn(dispatcher)

    private fun rawStream(e: Engine, prompt: String): Flow<String> = callbackFlow {
        val session = e.createSession()
        activeSession = session
        val lock = Any()
        var finished = false
        var closeWhenFinished = false
        fun finish() {
            synchronized(lock) {
                finished = true
                if (closeWhenFinished) runCatching { session.close() }
            }
        }
        session.generateContentStream(listOf(InputData.Text(prompt)), object : ResponseCallback {
            override fun onNext(response: String) { trySend(response) }
            override fun onDone() { finish(); close() }
            override fun onError(throwable: Throwable) {
                AppLog.e(CHAT, "raw session error", throwable)
                finish(); close(throwable)
            }
        })
        awaitClose {
            activeSession = null
            synchronized(lock) {
                if (finished) runCatching { session.close() }
                else {
                    // Closing while native code is still decoding is unsafe: cancel, close on callback.
                    closeWhenFinished = true
                    runCatching { session.cancelProcess() }
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    private fun String.quoted() = "\"" + replace("\n", "\\n") + "\""
}
