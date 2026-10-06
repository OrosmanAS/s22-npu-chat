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
    val thinking: Boolean = true,
    val chatMode: ChatMode = ChatMode.CONVERSATION,
    val logLevel: Level = Level.DEBUG,
    val nativeVerbose: Boolean = false,
    val autoInit: Boolean = true,
    val preloadLibs: Boolean = true,
    val benchmark: Boolean = true,
    val maxOutputTokens: Int = 0, // 0 = model default
    val systemPrompt: String = "",
    val variant: ModelVariant = ModelVariant.CTX_4K,
)

/** SharedPreferences-backed settings exposed as a StateFlow. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
            .putString("systemPrompt", next.systemPrompt)
            .putString("variant", next.variant.name)
            .apply()
        _state.value = next
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
