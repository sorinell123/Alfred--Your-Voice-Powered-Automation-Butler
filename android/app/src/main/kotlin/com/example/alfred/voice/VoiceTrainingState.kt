package com.example.alfred.voice

import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VoiceTrainingState {
    private val phraseIndexMutex = Mutex()
    private val _currentPhraseIndex = AtomicInteger(0)
    var currentPhraseIndex: Int
        get() = _currentPhraseIndex.get()
        set(value) {
            _currentPhraseIndex.set(value.coerceIn(0, TRAINING_PHRASES.size - 1))
            Log.d(TAG, "Set current phrase index to: $value")
        }
    
    private var _currentRecordingFile: File? = null
    var currentRecordingFile: File?
        get() = synchronized(this) { _currentRecordingFile }
        set(value) = synchronized(this) { 
            _currentRecordingFile = value
            Log.d(TAG, "Set current recording file to: ${value?.absolutePath}")
        }

    companion object {
        const val TAG = "VoiceTrainingService"
        const val RECORDING_DURATION = 5000L // Increased to 5 seconds for longer recording
        const val MIN_PROFILE_SAMPLES = 5 // Reduced for easier testing
        const val SAMPLE_RATE = 16000
        const val AUDIO_CHANNELS = 1
        const val ENCODING_BIT_RATE = 64000
        const val MIME_TYPE = "audio/aac"
        const val MIN_AMPLITUDE_THRESHOLD = 500 // Reduced threshold for more lenient recording
        const val MIN_SIGNAL_NOISE_RATIO = 5.0 // Reduced for more lenient recording

        val TRAINING_PHRASES = listOf(
            "Hey Alfred, show me the calendar",
            "Hey Alfred, what's my schedule",
            "Hey Alfred, open my tasks",
            "Hey Alfred, show me my reminders",
            "Hey Alfred, what's on my agenda"
        )

        fun isValidPhraseIndex(index: Int): Boolean {
            val isValid = index in 0 until TRAINING_PHRASES.size
            Log.d(TAG, "Checking phrase index validity: $index, isValid: $isValid")
            return isValid
        }
    }

    suspend fun updatePhraseIndex(newIndex: Int) = phraseIndexMutex.withLock {
        Log.d(TAG, "Updating phrase index from ${currentPhraseIndex} to $newIndex")
        currentPhraseIndex = newIndex
    }

    fun initializeEmptyProfile(profileMetadataFile: File) {
        try {
            Log.d(TAG, "Initializing empty profile at: ${profileMetadataFile.absolutePath}")
            
            val initialProfile = JSONObject().apply {
                put("profile", JSONObject().apply {
                    put("samples", JSONArray())
                    put("characteristics", JSONObject().apply {
                        put("rmsEnergy", 0.0)
                        put("zeroCrossingRate", 0.0)
                        put("volumeEnvelope", JSONArray(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))
                        put("maxAmplitude", 0.0)
                        put("lastUpdated", System.currentTimeMillis())
                    })
                    put("audioFormat", JSONObject().apply {
                        put("sampleRate", SAMPLE_RATE)
                        put("channels", AUDIO_CHANNELS)
                        put("bitRate", ENCODING_BIT_RATE)
                        put("mimeType", MIME_TYPE)
                    })
                    put("trained", false)
                    put("lastPhraseIndex", 0)
                    put("lastUpdated", System.currentTimeMillis())
                })
            }

            profileMetadataFile.parentFile?.mkdirs()
            profileMetadataFile.writeText(initialProfile.toString(2))
            Log.i(TAG, "Successfully initialized empty profile")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing empty profile: ${e.message}")
            e.printStackTrace()
            
            val basicProfile = """
                {
                    "profile": {
                        "samples": [],
                        "characteristics": {
                            "rmsEnergy": 0.0,
                            "zeroCrossingRate": 0.0,
                            "volumeEnvelope": [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
                            "maxAmplitude": 0.0,
                            "lastUpdated": ${System.currentTimeMillis()}
                        },
                        "audioFormat": {
                            "sampleRate": $SAMPLE_RATE,
                            "channels": $AUDIO_CHANNELS,
                            "bitRate": $ENCODING_BIT_RATE,
                            "mimeType": "$MIME_TYPE"
                        },
                        "trained": false,
                        "lastPhraseIndex": 0,
                        "lastUpdated": ${System.currentTimeMillis()}
                    }
                }
            """.trimIndent()
            
            profileMetadataFile.writeText(basicProfile)
            Log.i(TAG, "Initialized empty profile with fallback content")
        }
    }

    fun createProfileStatus(sampleCount: Int, trained: Boolean): Map<String, Any> {
        val status = mapOf(
            "sampleCount" to sampleCount,
            "trained" to trained,
            "requiredSamples" to MIN_PROFILE_SAMPLES
        )
        Log.d(TAG, "Created profile status: $status")
        return status
    }

    suspend fun getCurrentPhrase(): String = phraseIndexMutex.withLock {
        val phrase = TRAINING_PHRASES[currentPhraseIndex]
        Log.d(TAG, "Getting current phrase at index $currentPhraseIndex: $phrase")
        return phrase
    }

    fun resetState() {
        Log.d(TAG, "Resetting state")
        currentPhraseIndex = 0
        currentRecordingFile = null
    }
}
