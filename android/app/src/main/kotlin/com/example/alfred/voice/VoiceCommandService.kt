package com.example.alfred.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.os.Handler
import android.os.Looper

class VoiceCommandService(private val context: Context) {
    companion object {
        private const val TAG = VoiceCommandState.TAG
    }

    private var recognizer: SpeechRecognizer? = null
    private val isListening = AtomicBoolean(false)
    private var methodChannel: MethodChannel? = null
    private val isPaused = AtomicBoolean(true)
    private val commandMatcher = CommandMatcher()
    private var availableCommands = mutableListOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var currentMode = RecognitionMode.WAKE_WORD
    private val isInitializing = AtomicBoolean(false)
    private val isCleaningUp = AtomicBoolean(false)
    private val hasDetectedWakeWord = AtomicBoolean(false)
    private val COMMAND_RETRY_DELAY = VoiceCommandState.COMMAND_RETRY_DELAY

    // Helper function to invoke method channel on main thread
    private fun invokeMethodOnMain(method: String, arguments: Any? = null) {
        mainHandler.post {
            methodChannel?.invokeMethod(method, arguments)
        }
    }

    fun initialize(channel: MethodChannel) {
        methodChannel = channel
        GlobalScope.launch(Dispatchers.Main) {
            initializeRecognizer()
        }
        Log.d(VoiceCommandState.TAG, "Voice command service initialized with method channel")
    }

    private suspend fun initializeRecognizer() {
        if (isInitializing.get()) {
            Log.d(VoiceCommandState.TAG, "Recognition initialization already in progress")
            return
        }

        try {
            isInitializing.set(true)
            withContext(Dispatchers.Main) {
                if (recognizer == null) {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        Log.e(VoiceCommandState.TAG, "Speech recognition is not available on this device")
                        invokeMethodOnMain("onVoiceError", "Speech recognition is not available on this device")
                        return@withContext
                    }
                    
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(createRecognitionListener())
                    }
                    delay(500)
                    Log.d(VoiceCommandState.TAG, "Speech recognizer initialized successfully")
                    invokeMethodOnMain("onVoiceStatus", "initialized")
                }
            }
        } catch (e: Exception) {
            Log.e(VoiceCommandState.TAG, "Error initializing speech recognizer: ${e.message}")
            invokeMethodOnMain("onVoiceError", "Failed to initialize speech recognition: ${e.message}")
        } finally {
            isInitializing.set(false)
        }
    }

    private fun isWakeWord(spokenText: String): Boolean {
        val normalizedText = spokenText.trim().toLowerCase()
        if (VoiceCommandState.WAKE_WORD_VARIATIONS.contains(normalizedText)) {
            Log.d(VoiceCommandState.TAG, "Exact wake word detected: $normalizedText")
            return true
        }
        return false
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(VoiceCommandState.TAG, "Ready for speech in ${currentMode.name} mode")
            isListening.set(true)
            invokeMethodOnMain("onVoiceStatus", "ready")
        }

        override fun onBeginningOfSpeech() {
            Log.d(VoiceCommandState.TAG, "Beginning of speech in ${currentMode.name} mode")
            invokeMethodOnMain("onVoiceStatus", "listening")
        }

        override fun onRmsChanged(rmsdB: Float) {
            invokeMethodOnMain("onAudioLevel", rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Optional: Handle audio buffer
        }

        override fun onEndOfSpeech() {
            Log.d(VoiceCommandState.TAG, "Speech recognition stopped in ${currentMode.name} mode")
            isListening.set(false)
            invokeMethodOnMain("onVoiceStatus", "processing")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech input detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                13 -> "Speech recognizer internal error"
                else -> "Unknown error code: $error"
            }
            
            Log.e(VoiceCommandState.TAG, "Speech recognition error in ${currentMode.name} mode: $errorMessage")
            isListening.set(false)
            invokeMethodOnMain("onVoiceError", errorMessage)
            
            // Only perform cleanup for severe errors
            GlobalScope.launch(Dispatchers.Main) {
                when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // For busy errors, wait longer before retrying
                        delay(2000) // Longer delay for busy error
                        if (!isPaused.get()) {
                            startListeningForWakeWord() // Always return to wake word mode
                        }
                    }
                    13, // Internal error
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // For severe errors, recreate recognizer after delay
                        recognizer?.destroy()
                        recognizer = null
                        delay(1500) // Wait before creating new recognizer
                        if (!isPaused.get()) {
                            initializeRecognizer()
                            delay(500) // Additional stabilization delay
                            startListeningForWakeWord() // Always return to wake word mode
                        }
                    }
                    else -> {
                        // For minor errors just retry in current mode after a brief delay
                        if (!isPaused.get()) {
                            delay(COMMAND_RETRY_DELAY)
                            when (currentMode) {
                                RecognitionMode.WAKE_WORD -> startListeningForWakeWord()
                                RecognitionMode.COMMAND -> startListeningForCommand()
                            }
                        }
                    }
                }
            }
        }

        override fun onPartialResults(params: Bundle?) {
            val matches = params?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0].toLowerCase()
                Log.d(VoiceCommandState.TAG, "Partial result in ${currentMode.name} mode: $spokenText")
                
                when (currentMode) {
                    RecognitionMode.WAKE_WORD -> {
                        invokeMethodOnMain("onListeningFeedback", mapOf(
                            "mode" to "wake_word",
                            "text" to spokenText
                        ))
                    }
                    RecognitionMode.COMMAND -> {
                        invokeMethodOnMain("onListeningFeedback", mapOf(
                            "mode" to "command",
                            "text" to spokenText
                        ))
                    }
                }
            }
        }

        override fun onResults(params: Bundle?) {
            val matches = params?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0].toLowerCase()
                Log.d(VoiceCommandState.TAG, "Final result in ${currentMode.name} mode: $spokenText")
                
                when (currentMode) {
                    RecognitionMode.WAKE_WORD -> {
                        if (isWakeWord(spokenText)) {
                            Log.d(VoiceCommandState.TAG, "Wake word detected in final results")
                            invokeMethodOnMain("onWakeWordDetected")
                            GlobalScope.launch(Dispatchers.Main) {
                                currentMode = RecognitionMode.COMMAND
                                startListeningForCommand()
                            }
                        } else {
                            // Keep listening for wake word
                            GlobalScope.launch(Dispatchers.Main) {
                                startListeningForWakeWord()
                            }
                        }
                    }
                    RecognitionMode.COMMAND -> {
                        // Check if command is valid and execute
                        val command = commandMatcher.findBestMatch(spokenText, availableCommands)
                        if (command != null) {
                            // Valid command found - execute it
                            invokeMethodOnMain("onCommandReceived", command)
                            // Stop listening after successful command execution
                            GlobalScope.launch(Dispatchers.Main) {
                                stopListening()
                            }
                        } else {
                            // No valid command - keep listening
                            GlobalScope.launch(Dispatchers.Main) {
                                delay(COMMAND_RETRY_DELAY)
                                startListeningForCommand()
                            }
                        }
                    }
                }
            } else {
                // No results - continue listening in current mode
                if (!isPaused.get()) {
                    GlobalScope.launch(Dispatchers.Main) {
                        when (currentMode) {
                            RecognitionMode.WAKE_WORD -> startListeningForWakeWord()
                            RecognitionMode.COMMAND -> startListeningForCommand()
                        }
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            Log.d(VoiceCommandState.TAG, "Speech recognition event: type=$eventType")
        }
    }

    private suspend fun startListeningForWakeWord() {
        withContext(Dispatchers.Main) {
            if (isPaused.get()) {
                Log.d(VoiceCommandState.TAG, "Voice recognition is paused")
                return@withContext
            }

            try {
                // Stop any existing listening
                recognizer?.stopListening()
                if (recognizer == null) {
                    initializeRecognizer()
                }

                currentMode = RecognitionMode.WAKE_WORD
                hasDetectedWakeWord.set(false)
                isListening.set(false) // Reset state before starting
                
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                
                recognizer?.startListening(intent)
                Log.d(VoiceCommandState.TAG, "Started listening for wake word")
                
            } catch (e: Exception) {
                Log.e(VoiceCommandState.TAG, "Error in startListening: ${e.message}")
                invokeMethodOnMain("onVoiceError", "Failed to start speech recognition: ${e.message}")
                if (recognizer == null) {
                    delay(COMMAND_RETRY_DELAY)
                    initializeRecognizer()
                }
            }
        }
    }

    private suspend fun startListeningForCommand() {
        withContext(Dispatchers.Main) {
            if (isPaused.get()) {
                Log.d(VoiceCommandState.TAG, "Voice recognition is paused")
                return@withContext
            }

            try {
                // Stop any existing listening
                recognizer?.stopListening()
                if (recognizer == null) {
                    initializeRecognizer()
                }

                currentMode = RecognitionMode.COMMAND
                isListening.set(false) // Reset state before starting
                
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                }
                
                recognizer?.startListening(intent)
                Log.d(VoiceCommandState.TAG, "Started listening for command")
                invokeMethodOnMain("onListeningForCommand")
                
            } catch (e: Exception) {
                Log.e(VoiceCommandState.TAG, "Error in startListeningForCommand: ${e.message}")
                invokeMethodOnMain("onVoiceError", "Failed to start command recognition: ${e.message}")
                // Return to wake word mode but keep recognizer alive
                currentMode = RecognitionMode.WAKE_WORD
                isPaused.set(true)
                invokeMethodOnMain("onServiceStopped")
            }
        }
    }

    suspend fun startListening() {
        withContext(Dispatchers.Main) {
            try {
                // Stop any existing listening first
                recognizer?.stopListening()
                isPaused.set(false)
                isListening.set(false) // Reset state before starting
                currentMode = RecognitionMode.WAKE_WORD
                
                if (recognizer == null) {
                    initializeRecognizer()
                }
                startListeningForWakeWord()
            } catch (e: Exception) {
                Log.e(VoiceCommandState.TAG, "Error starting recognition: ${e.message}")
                invokeMethodOnMain("onVoiceError", "Failed to start recognition: ${e.message}")
                isPaused.set(true)
                isListening.set(false)
            }
        }
    }

    suspend fun stopListening() {
        withContext(Dispatchers.Main) {
            try {
                recognizer?.stopListening()
                recognizer?.destroy()
                recognizer = null
                isListening.set(false)
                isPaused.set(true)
                currentMode = RecognitionMode.WAKE_WORD
                Log.d(VoiceCommandState.TAG, "Speech recognition stopped")
                invokeMethodOnMain("onVoiceStatus", "stopped")
            } catch (e: Exception) {
                Log.e(VoiceCommandState.TAG, "Error stopping recognition: ${e.message}")
            }
        }
    }

    suspend fun updateAvailableCommands(commands: List<String>) {
        withContext(Dispatchers.Main) {
            try {
                availableCommands = commands.toMutableList()
                Log.d(VoiceCommandState.TAG, "Updated available commands: $commands")
            } catch (e: Exception) {
                Log.e(VoiceCommandState.TAG, "Error updating commands: ${e.message}")
            }
        }
    }

    fun pause() {
        isPaused.set(true)
        GlobalScope.launch(Dispatchers.Main) {
            if (isListening.get()) {
                stopListening()
            }
        }
        Log.d(VoiceCommandState.TAG, "Voice recognition paused")
    }

    fun resume() {
        isPaused.set(false)
        GlobalScope.launch(Dispatchers.Main) {
            startListeningForWakeWord()
        }
        Log.d(VoiceCommandState.TAG, "Voice recognition resumed")
    }

    fun destroy() {
        GlobalScope.launch(Dispatchers.Main) {
            cleanup()
        }
    }

    suspend fun cleanup() {
        if (!isCleaningUp.compareAndSet(false, true)) {
            return
        }

        try {
            withContext(Dispatchers.Main) {
                try {
                    recognizer?.destroy()
                    recognizer = null
                    isListening.set(false)
                    Log.d(VoiceCommandState.TAG, "Voice command service cleaned up")
                } catch (e: Exception) {
                    Log.e(VoiceCommandState.TAG, "Error cleaning up: ${e.message}")
                }
            }
        } finally {
            isCleaningUp.set(false)
        }
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }
}
