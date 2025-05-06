package com.example.alfred.voice

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.media.AudioManager
import android.media.AudioAttributes

class VoiceTrainingService private constructor(context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private val state = VoiceTrainingState()
    private val profileManager = VoiceProfileManager(context)
    private val voiceAnalyzer = VoiceAnalyzer()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var methodChannel: MethodChannel? = null
    private val recordingMutex = Mutex()
    private var isRecording = false
    private var recordingJob: Job? = null
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var isTrainingInProgress = false

    companion object {
        private const val DELAY_BETWEEN_RECORDINGS = 2000L // 2 seconds delay between recordings
        
        @Volatile
        private var instance: VoiceTrainingService? = null
        
        fun getInstance(context: Context): VoiceTrainingService =
            instance ?: synchronized(this) {
                instance ?: VoiceTrainingService(context).also { instance = it }
            }
    }

    init {
        runBlocking {
            state.updatePhraseIndex(profileManager.getNextPhraseIndex())
        }
    }

    fun initialize(channel: MethodChannel) {
        methodChannel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getCurrentTrainingPhrase" -> {
                    scope.launch {
                        result.success(state.getCurrentPhrase())
                    }
                }
                "startProfileTraining" -> {
                    scope.launch {
                        if (!isTrainingInProgress) {
                            isTrainingInProgress = true
                            // Notify Flutter about the first phrase
                            methodChannel?.invokeMethod("onTrainingStarted", state.getCurrentPhrase())
                            result.success(startProfileTraining())
                        } else {
                            result.success(false)
                        }
                    }
                }
                "stopRecording" -> {
                    scope.launch {
                        isTrainingInProgress = false
                        val success = stopRecording()
                        result.success(success)
                        methodChannel?.invokeMethod("onTrainingCancelled", null)
                    }
                }
                "getProfileStatus" -> {
                    try {
                        val status = getProfileStatus()
                        val map = mapOf(
                            "sampleCount" to status.optInt("sampleCount", 0),
                            "trained" to status.optBoolean("trained", false),
                            "requiredSamples" to status.optInt("requiredSamples", VoiceTrainingState.MIN_PROFILE_SAMPLES)
                        )
                        Log.i(VoiceTrainingState.TAG, "Received profile status result: $map")
                        result.success(map)
                    } catch (e: Exception) {
                        Log.e(VoiceTrainingState.TAG, "Error getting profile status: ${e.message}")
                        val defaultMap = mapOf(
                            "sampleCount" to 0,
                            "trained" to false,
                            "requiredSamples" to VoiceTrainingState.MIN_PROFILE_SAMPLES
                        )
                        result.success(defaultMap)
                    }
                }
                "resetProfile" -> {
                    scope.launch {
                        val success = resetProfile()
                        result.success(success)
                    }
                }
                "isProfileTrained" -> {
                    result.success(isProfileTrained())
                }
                "matchVoiceProfile" -> {
                    val audioData = call.argument<ByteArray>("audioData")
                    if (audioData != null) {
                        result.success(matchVoiceProfile(audioData))
                    } else {
                        result.error("INVALID_ARGUMENT", "Audio data is required", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun ensureCleanState() {
        cleanupRecorder()
        state.resetState()
    }

    private suspend fun initializeMediaRecorder(): Boolean {
        return try {
            // Configure audio settings for recording
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isMicrophoneMute = false

            val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(VoiceTrainingState.SAMPLE_RATE)
                
                // Create recording file with .aac extension
                val recordingFile = File(profileManager.getVoiceProfileDir(), "profile_sample_${System.currentTimeMillis()}.aac")
                state.currentRecordingFile = recordingFile
                Log.d(VoiceTrainingState.TAG, "Recording to file: ${recordingFile.absolutePath}")
                
                setOutputFile(recordingFile.absolutePath)
                prepare()
            }

            mediaRecorder = recorder
            true
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Failed to initialize MediaRecorder: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    public suspend fun startProfileTraining(): Boolean = recordingMutex.withLock {
        try {
            val status = getProfileStatus()
            if (status.optInt("sampleCount", 0) >= VoiceTrainingState.MIN_PROFILE_SAMPLES) {
                Log.w(VoiceTrainingState.TAG, "Maximum number of samples already collected")
                isTrainingInProgress = false
                methodChannel?.invokeMethod("onTrainingComplete", null)
                return false
            }

            val currentPhrase = state.getCurrentPhrase()
            Log.i(VoiceTrainingState.TAG, "Starting voice profile training with phrase: $currentPhrase")

            if (!initializeMediaRecorder()) {
                isTrainingInProgress = false
                methodChannel?.invokeMethod("onTrainingError", "Failed to initialize recorder")
                return false
            }

            try {
                mediaRecorder?.start()
                isRecording = true
                Log.i(VoiceTrainingState.TAG, "Started recording voice profile sample")
                
                // Start countdown timer for recording duration
                recordingJob = scope.launch {
                    delay(VoiceTrainingState.RECORDING_DURATION)
                    handleRecordingCompletion()
                }
                
                return true
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Failed to start recording: ${e.message}")
                e.printStackTrace()
                cleanupRecorder()
                isTrainingInProgress = false
                methodChannel?.invokeMethod("onTrainingError", "Failed to start recording")
                return false
            }
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error in startProfileTraining: ${e.message}")
            e.printStackTrace()
            cleanupRecorder()
            isTrainingInProgress = false
            methodChannel?.invokeMethod("onTrainingError", "Unexpected error during training")
            return false
        }
    }

    private suspend fun handleRecordingCompletion() {
        try {
            if (isRecording) {
                val success = stopRecording()
                Log.d(VoiceTrainingState.TAG, "Recording stopped with success: $success")
                
                if (isTrainingInProgress && success) {
                    val status = getProfileStatus()
                    val currentCount = status.optInt("sampleCount", 0)
                    
                    if (currentCount < VoiceTrainingState.MIN_PROFILE_SAMPLES) {
                        // Notify progress on Main thread
                        withContext(Dispatchers.Main) {
                            methodChannel?.invokeMethod("onTrainingProgress", mapOf(
                                "samplesCollected" to currentCount,
                                "totalRequired" to VoiceTrainingState.MIN_PROFILE_SAMPLES
                            ))
                            
                            val nextPhrase = state.getCurrentPhrase()
                            methodChannel?.invokeMethod("onNextPhrase", nextPhrase)
                        }
                        
                        // Launch a new coroutine for the next recording
                        scope.launch {
                            delay(1000) // Add a small delay between
                        }
                    } else {
                        isTrainingInProgress = false
                        withContext(Dispatchers.Main) {
                            methodChannel?.invokeMethod("onTrainingComplete", null)
                        }
                    }
                } else {
                    Log.e(VoiceTrainingState.TAG, "Failed to stop recording or training not in progress")
                    withContext(Dispatchers.Main) {
                        methodChannel?.invokeMethod("onTrainingError", "Failed to process recording")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error in handleRecordingCompletion: ${e.message}")
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                methodChannel?.invokeMethod("onTrainingError", "Unexpected error during recording completion")
            }
        }
    }

    private fun cleanupRecorder() {
        try {
            if (isRecording) {
                mediaRecorder?.apply {
                    try {
                        stop()
                    } catch (e: Exception) {
                        Log.e(VoiceTrainingState.TAG, "Error stopping MediaRecorder: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error in initial cleanup: ${e.message}")
        } finally {
            try {
                mediaRecorder?.release()
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error releasing MediaRecorder: ${e.message}")
            }
            mediaRecorder = null
            isRecording = false
            recordingJob?.cancel()
            recordingJob = null
        }
    }

    public suspend fun stopRecording(): Boolean = recordingMutex.withLock {
        Log.d(VoiceTrainingState.TAG, "Acquired mutex for stopRecording")
        if (!isRecording) {
            Log.d(VoiceTrainingState.TAG, "No recording in progress to stop")
            return true
        }

        Log.i(VoiceTrainingState.TAG, "Stopping voice profile recording")
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        
        var recordingStopped = false
        try {
            mediaRecorder?.apply {
                try {
                    Log.d(VoiceTrainingState.TAG, "Attempting to stop MediaRecorder")
                    stop()
                    recordingStopped = true
                    Log.d(VoiceTrainingState.TAG, "MediaRecorder stopped successfully")
                } catch (e: IllegalStateException) {
                    Log.e(VoiceTrainingState.TAG, "Error stopping MediaRecorder: ${e.message}")
                    e.printStackTrace()
                }
                Log.d(VoiceTrainingState.TAG, "Releasing MediaRecorder")
                release()
                Log.d(VoiceTrainingState.TAG, "MediaRecorder released successfully")
            }
            mediaRecorder = null
            
            return if (recordingStopped) {
                Log.d(VoiceTrainingState.TAG, "Proceeding to process recorded file")
                processRecordedFile()
            } else {
                Log.e(VoiceTrainingState.TAG, "Recording was not stopped properly")
                false
            }
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error in stopRecording: ${e.message}")
            e.printStackTrace()
            cleanupRecorder()
            return false
        }
    }

    private suspend fun processRecordedFile(): Boolean {
        val file = state.currentRecordingFile
        Log.d(VoiceTrainingState.TAG, "Processing recorded file: ${file?.absolutePath}")
        
        if (file == null) {
            Log.e(VoiceTrainingState.TAG, "No recording file available")
            return false
        }

        if (!file.exists() || file.length() == 0L) {
            Log.e(VoiceTrainingState.TAG, "Recording file is empty or does not exist")
            file.delete()
            return false
        }

        return try {
            Log.i(VoiceTrainingState.TAG, "Voice profile sample recorded successfully: ${file.name} (${file.length()} bytes)")
            Log.d(VoiceTrainingState.TAG, "Extracting voice characteristics")
            val characteristics = voiceAnalyzer.extractVoiceCharacteristics(file)
            Log.d(VoiceTrainingState.TAG, "Extracted voice characteristics: $characteristics")
            
            Log.d(VoiceTrainingState.TAG, "Updating profile metadata")
            profileManager.updateProfileMetadata(file, characteristics)
            
            val status = getProfileStatus()
            val currentCount = status.optInt("sampleCount", 0)
            Log.i(VoiceTrainingState.TAG, "Current sample count after processing: $currentCount")
            
            if (currentCount < VoiceTrainingState.MIN_PROFILE_SAMPLES) {
                val currentIndex = state.currentPhraseIndex
                Log.d(VoiceTrainingState.TAG, "Updating phrase index from $currentIndex")
                profileManager.updatePhraseIndex(currentIndex)
                state.updatePhraseIndex(profileManager.getNextPhraseIndex())
                Log.d(VoiceTrainingState.TAG, "Updated to next phrase index: ${state.currentPhraseIndex}")
            }
            true
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error processing recorded file: ${e.message}")
            e.printStackTrace()
            file.delete()
            false
        }
    }

    fun getProfileStatus(): JSONObject {
        return profileManager.getProfileStatus()
    }

    public suspend fun resetProfile(): Boolean {
        isTrainingInProgress = false
        return try {
            profileManager.resetProfile()
            state.resetState()
            cleanupRecorder()
            true
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error resetting profile: ${e.message}")
            false
        }
    }

    fun isProfileTrained(): Boolean {
        return profileManager.isProfileTrained()
    }

    fun matchVoiceProfile(audioData: ByteArray): Boolean {
        if (!isProfileTrained()) {
            Log.w(VoiceTrainingState.TAG, "Cannot match voice profile - profile not trained")
            return false
        }

        val profileCharacteristics = profileManager.getProfileCharacteristics()
        if (profileCharacteristics == null) {
            Log.e(VoiceTrainingState.TAG, "No voice profile characteristics available")
            return false
        }

        return voiceAnalyzer.matchVoiceProfile(audioData, profileCharacteristics)
    }

    suspend fun getCurrentTrainingPhrase(): String {
        return state.getCurrentPhrase()
    }
}
