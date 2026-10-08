package com.npuchat.engine

/** Qwen chat-format helpers for the raw-session path (the format the model card's CLI uses). */
object QwenPrompt {
    const val STOP = "<|im_end|>"

    fun render(system: String, history: List<Pair<String, String>>, user: String, thinking: Boolean): String = buildString {
        if (system.isNotBlank()) append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
        for ((u, a) in history) {
            append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
            append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        // Thinking on ends the prompt with "<think>\n"; off pre-fills an empty think block.
        append(if (thinking) "<think>\n" else "<think>\n\n</think>\n\n")
    }
}

/** Splits streamed text into reasoning and answer when the runtime leaves <think> tags inline. */
object ThinkSplit {
    data class Parts(val thought: String, val answer: String, val thinkingOpen: Boolean)

    /** [startsInside] is true when the prompt already opened the think block (raw mode, thinking on). */
    fun split(raw: String, startsInside: Boolean): Parts {
        var text = raw.replace(QwenPrompt.STOP, "")
        if (startsInside && !text.trimStart().startsWith("<think>")) text = "<think>$text"
        val open = text.indexOf("<think>")
        if (open < 0) return Parts("", text.trim(), false)
        val close = text.indexOf("</think>", open)
        val before = text.substring(0, open)
        return if (close < 0) {
            Parts(text.substring(open + 7).trim(), before.trim(), true)
        } else {
            Parts(text.substring(open + 7, close).trim(), (before + text.substring(close + 8)).trim(), false)
        }
    }
}

/**
 * Last-resort guard against greedy-decoding loops: true when the tail of [text] is the same block
 * repeated [repeats] times in a row (blocks of 20..600 chars).
 */
object LoopDetector {
    fun isLooping(text: CharSequence, repeats: Int = 3): Boolean {
        val n = text.length
        for (period in 20..600) {
            if (period * repeats > n) break
            val start = n - period * repeats
            var same = true
            for (i in 0 until period * (repeats - 1)) {
                if (text[start + i] != text[start + i + period]) { same = false; break }
            }
            if (same) return true
        }
        return false
    }
}
