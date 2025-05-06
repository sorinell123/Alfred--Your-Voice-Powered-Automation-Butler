package com.example.alfred.voice

import java.util.concurrent.atomic.AtomicBoolean

class VoiceCommandState {
    val isListening = AtomicBoolean(false)
    val isPaused = AtomicBoolean(false)
    val isInitializing = AtomicBoolean(false)
    var currentMode = RecognitionMode.WAKE_WORD
    var availableCommands = mutableListOf<String>()
    var lastAudioData: ByteArray? = null
    var retryCount = 0
    var skipVoiceVerification = true

    companion object {
        const val TAG = "VoiceCommandService"
        const val COMMAND_RETRY_DELAY = 500L // Short delay between command retries
        const val MAX_RETRY_DELAY_MS = 5000L // Maximum retry delay
        const val RETRY_DELAY_MS = 1000L // Base retry delay
        const val SIMILARITY_THRESHOLD = 0.65  // Aligned with VoiceAnalyzer
        const val WAKE_WORD = "hey alfred"
        val WAKE_WORD_VARIATIONS = setOf(
            "hey alfred",
            "hey offered",
            "hey alford",
            "he alfred",
            "hey al fred",
            "hi alfred",
            "hei alfred",
            "alfred hey"
        )
    }
}

enum class RecognitionMode {
    WAKE_WORD,
    COMMAND
}
