package com.example.alfred.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

class VoiceRecognitionHandler(
    private val context: Context,
    private val state: VoiceCommandState,
    private val methodChannel: MethodChannel?,
    private val commandMatcher: CommandMatcher,
    private val voiceTrainingService: VoiceTrainingService
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(VoiceCommandState.TAG, "Speech recognition is not available on this device")
            methodChannel?.invokeMethod("onVoiceError", "Speech recognition not available")
            return
        }

        try {
            initializeSpeechRecognizer()
            Log.d(VoiceCommandState.TAG, "Speech recognition initialized successfully")
        } catch (e: Exception) {
            Log.e(VoiceCommandState.TAG, "Error initializing speech recognizer: ${e.message}")
            methodChannel?.invokeMethod("onVoiceError", "Failed to initialize: ${e.message}")
        }
    }

    private fun initializeSpeechRecognizer() {
        if (state.isInitializing.get()) {
            return
        }

        state.isInitializing.set(true)

        try {
            destroyRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            setupRecognizer()
            Log.d(VoiceCommandState.TAG, "Speech recognizer initialized")
        } catch (e: Exception) {
            Log.e(VoiceCommandState.TAG, "Error in initializeSpeechRecognizer: ${e.message}")
            methodChannel?.invokeMethod("onVoiceError", "Failed to initialize speech recognizer")
        } finally {
            state.isInitializing.set(false)
        }
    }

    private fun setupRecognizer() {
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                handler.post {
                    Log.d(VoiceCommandState.TAG, "Ready for speech in mode: ${state.currentMode}")
                    state.isListening.set(true)

                    val status = if (!voiceTrainingService.isProfileTrained()) {
                        val profileStatus = voiceTrainingService.getProfileStatus()
                        val sampleCount = profileStatus.optInt("sampleCount", 0)
                        val requiredSamples = profileStatus.optInt("requiredSamples", 5)
                        "Voice profile training needed. $sampleCount/$requiredSamples samples collected"
                    } else if (state.currentMode == RecognitionMode.WAKE_WORD) {
                        "Listening for wake word 'Hey Alfred'"
                    } else {
                        "Waiting for command"
                    }

                    methodChannel?.invokeMethod("onVoiceStatus", status)
                }
            }

            override fun onBeginningOfSpeech() {
                handler.post {
                    Log.d(VoiceCommandState.TAG, "Speech began in mode: ${state.currentMode}")
                    methodChannel?.invokeMethod("onVoiceStatus", "listening")
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Optional: Implement if you want to show voice level
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                state.lastAudioData = buffer
            }

            override fun onEndOfSpeech() {
                handler.post {
                    Log.d(VoiceCommandState.TAG, "Speech ended in mode: ${state.currentMode}")
                    state.isListening.set(false)
                    methodChannel?.invokeMethod("onVoiceStatus", "processing")
                }
            }

            override fun onError(error: Int) {
                handler.post {
                    handleRecognitionError(error)
                }
            }

            override fun onResults(results: Bundle?) {
                handler.post {
                    handleRecognitionResults(results)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handler.post {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val partialText = matches[0].toLowerCase()
                        Log.d(VoiceCommandState.TAG, "Partial result received: $partialText in mode: ${state.currentMode}")
                        
                        // Send feedback through method channel
                        methodChannel?.invokeMethod("onListeningFeedback", mapOf(
                            "mode" to if (state.currentMode == RecognitionMode.COMMAND) "command" else "wake_word",
                            "text" to partialText
                        ))
                        
                        // Also send through specific partial result channels
                        when (state.currentMode) {
                            RecognitionMode.WAKE_WORD -> {
                                methodChannel?.invokeMethod("onPartialSpeechRecognized", partialText)
                            }
                            RecognitionMode.COMMAND -> {
                                methodChannel?.invokeMethod("onPartialCommandRecognized", partialText)
                            }
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Implementation not needed for basic functionality
            }
        }
    }

    private fun handleRecognitionError(error: Int) {
        val errorMessage = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech input detected"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            else -> "Recognition error"
        }

        Log.e(VoiceCommandState.TAG, "Speech recognition error in mode ${state.currentMode}: $errorMessage ($error)")
        state.isListening.set(false)

        when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                handler.postDelayed({
                    if (!state.isPaused.get()) {
                        initializeSpeechRecognizer()
                        startListening()
                    }
                }, VoiceCommandState.MAX_RETRY_DELAY_MS)
            }
            SpeechRecognizer.ERROR_CLIENT -> {
                destroyRecognizer()
                handler.postDelayed({
                    if (!state.isPaused.get()) {
                        initializeSpeechRecognizer()
                        startListening()
                    }
                }, VoiceCommandState.RETRY_DELAY_MS * 2)
            }
            else -> {
                if (!state.isPaused.get()) {
                    handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
                }
            }
        }
    }

    private fun handleRecognitionResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val command = matches[0].toLowerCase()
            Log.d(VoiceCommandState.TAG, "Recognized in mode ${state.currentMode}: $command")

            val isProfileTrained = voiceTrainingService.isProfileTrained()
            val isVoiceVerified = if (state.skipVoiceVerification || !isProfileTrained) {
                true
            } else {
                state.lastAudioData?.let { audioData ->
                    voiceTrainingService.matchVoiceProfile(audioData)
                } ?: false
            }

            if (!isVoiceVerified && !state.skipVoiceVerification) {
                Log.w(VoiceCommandState.TAG, "Voice verification failed")
                methodChannel?.invokeMethod("onVoiceError", 
                    "Voice not recognized. Please ensure you've completed voice training.")
                handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
                return
            } else {
                Log.i(VoiceCommandState.TAG, "Voice matched the voice profile")
                methodChannel?.invokeMethod("onVoiceVerified", "Voice matched the profile")
            }

            when (state.currentMode) {
                RecognitionMode.WAKE_WORD -> {
                    if (VoiceCommandState.WAKE_WORD_VARIATIONS.any { command.contains(it) }) {
                        Log.d(VoiceCommandState.TAG, "Wake word detected")
                        state.currentMode = RecognitionMode.COMMAND
                        state.skipVoiceVerification = false
                        methodChannel?.invokeMethod("onVoiceStatus", "Wake word detected. Please say a command.")
                        handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
                    } else {
                        Log.d(VoiceCommandState.TAG, "Continuing to listen for wake word")
                        // Keep listening for wake word continuously
                        handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
                    }
                }
                RecognitionMode.COMMAND -> {
                    val bestMatch = commandMatcher.findBestMatch(command, state.availableCommands)
                    if (bestMatch != null) {
                        Log.d(VoiceCommandState.TAG, "Found command match: $bestMatch")
                        methodChannel?.invokeMethod("onVoiceResult", bestMatch)
                        // Return to wake word mode after successful command
                        state.currentMode = RecognitionMode.WAKE_WORD
                        handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
                    } else {
                        Log.d(VoiceCommandState.TAG, "No matching command found, retrying")
                        methodChannel?.invokeMethod("onVoiceError", "Command not recognized, please try again")
                        // Keep listening for commands
                        handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
                    }
                }
            }
        } else {
            // No results - continue listening in current mode
            handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
        }
    }

    fun startListening() {
        if (state.isPaused.get() || state.isListening.get() || state.isInitializing.get()) {
            Log.d(VoiceCommandState.TAG, "Skipping startListening because service is paused, already listening, or initializing")
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            speechRecognizer?.startListening(intent)
            Log.d(VoiceCommandState.TAG, "Speech recognition started in mode: ${state.currentMode}")
        } catch (e: Exception) {
            Log.e(VoiceCommandState.TAG, "Error starting speech recognition: ${e.message}")
            methodChannel?.invokeMethod("onVoiceError", "Failed to start: ${e.message}")
            state.isListening.set(false)
        }
    }

    private fun handleError(error: String) {
        Log.e(VoiceCommandState.TAG, error)
        methodChannel?.invokeMethod("onVoiceError", error)
        
        if (!state.isPaused.get()) {
            // Continue listening with a delay
            handler.postDelayed({ startListening() }, VoiceCommandState.COMMAND_RETRY_DELAY)
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            state.isListening.set(false)
            state.currentMode = RecognitionMode.WAKE_WORD
            state.skipVoiceVerification = true
            handler.removeCallbacksAndMessages(null)
            state.isPaused.set(true)
            Log.d(VoiceCommandState.TAG, "Speech recognition stopped")
        } catch (e: Exception) {
            Log.e(VoiceCommandState.TAG, "Error stopping speech recognition: ${e.message}")
        }
    }

    fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            state.isListening.set(false)
            Log.d(VoiceCommandState.TAG, "Speech recognizer destroyed")
        } catch (e: Exception) {
            Log.e(VoiceCommandState.TAG, "Error destroying speech recognizer: ${e.message}")
        }
    }
}
