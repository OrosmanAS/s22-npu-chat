package com.npuchat

import android.content.Context
import android.content.SharedPreferences
import com.npuchat.log.Level
import com.npuchat.model.ModelVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ChatMode(val label: String) {
    CONVERSATION("Conversation API (chat template from the model)"),
    RAW_SESSION("Raw session (hand-built Qwen prompt, like the CLI)"),
}

data class SettingsState(
    val thinking: Boolean = false,
    val chatMode: ChatMode = ChatMode.CONVERSATION,
    val logLevel: Level = Level.DEBUG,
    val nativeVerbose: Boolean = false,
    val autoInit: Boolean = true,
    val preloadLibs: Boolean = true,
    val benchmark: Boolean = true,
    val maxOutputTokens: Int = 2048, // 0 = model default (can fill the whole context)
    // Anti-loop controls. The NPU decodes greedily, so small models can repeat forever; LiteRT-LM
    // applies these as logit constraints before the greedy pick (they work on the NPU path).
    val thinkingBudget: Int = 1024, // max thinking tokens, then </think> is forced; 0 = unlimited
    val repetitionPenalty: Float = 1.1f, // 1.0 = off
    val noRepeatNgram: Int = 12, // block repeating any 12-token sequence; 0 = off
    val systemPrompt: String = "",
    val variant: ModelVariant = ModelVariant.CTX_4K,
)

/** SharedPreferences-backed settings exposed as a StateFlow. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    init { migrate() }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state
    val current: SettingsState get() = _state.value

    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean("thinking", next.thinking)
            .putString("chatMode", next.chatMode.name)
            .putString("logLevel", next.logLevel.name)
            .putBoolean("nativeVerbose", next.nativeVerbose)
            .putBoolean("autoInit", next.autoInit)
            .putBoolean("preloadLibs", next.preloadLibs)
            .putBoolean("benchmark", next.benchmark)
            .putInt("maxOutputTokens", next.maxOutputTokens)
            .putInt("thinkingBudget", next.thinkingBudget)
            .putFloat("repetitionPenalty", next.repetitionPenalty)
            .putInt("noRepeatNgram", next.noRepeatNgram)
            .putString("systemPrompt", next.systemPrompt)
            .putString("variant", next.variant.name)
            .apply()
        _state.value = next
    }

    private fun migrate() {
        val version = prefs.getInt("version", 1)
        if (version < 2) {
            // v1 defaults let a thinking loop fill the whole context (seen on the S22+).
            val e = prefs.edit().putInt("version", 2)
            if (prefs.getInt("maxOutputTokens", 0) == 0) e.putInt("maxOutputTokens", 2048)
            e.apply()
        }
    }

    private fun load(): SettingsState {
        val d = SettingsState()
        return SettingsState(
            thinking = prefs.getBoolean("thinking", d.thinking),
            chatMode = enumOr(prefs.getString("chatMode", null), d.chatMode),
            logLevel = enumOr(prefs.getString("logLevel", null), d.logLevel),
            nativeVerbose = prefs.getBoolean("nativeVerbose", d.nativeVerbose),
            autoInit = prefs.getBoolean("autoInit", d.autoInit),
            preloadLibs = prefs.getBoolean("preloadLibs", d.preloadLibs),
            benchmark = prefs.getBoolean("benchmark", d.benchmark),
            maxOutputTokens = prefs.getInt("maxOutputTokens", d.maxOutputTokens),
            thinkingBudget = prefs.getInt("thinkingBudget", d.thinkingBudget),
            repetitionPenalty = prefs.getFloat("repetitionPenalty", d.repetitionPenalty),
            noRepeatNgram = prefs.getInt("noRepeatNgram", d.noRepeatNgram),
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt) ?: "",
            variant = enumOr(prefs.getString("variant", null), d.variant),
        )
    }

    // Crash guard: set (synchronously) right before NPU init, cleared once init returns.
    // If it is still set at the next launch, the previous init killed the process.
    var initInProgress: Boolean
        get() = prefs.getBoolean("initInProgress", false)
        set(value) { prefs.edit().putBoolean("initInProgress", value).commit() }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
