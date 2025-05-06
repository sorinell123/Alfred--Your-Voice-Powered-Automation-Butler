package com.example.alfred.voice

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class VoiceProfileManager(context: Context) {
    private val voiceProfileDir: File
    private val profileMetadataFile: File
    private val state = VoiceTrainingState()
    private val metadataLock = ReentrantLock()
    private val appContext = context.applicationContext

    init {
        // Get the external files directory
        val baseDir = appContext.getExternalFilesDir(null)
        Log.d(VoiceTrainingState.TAG, "Base external directory: ${baseDir?.absolutePath}")
        
        // Create voice profile directory
        voiceProfileDir = File(baseDir, "voice_profile").apply {
            if (!exists()) {
                val created = mkdirs()
                Log.i(VoiceTrainingState.TAG, "Created voice profile directory at ${absolutePath}, success: $created")
            } else {
                Log.i(VoiceTrainingState.TAG, "Voice profile directory exists at ${absolutePath}")
            }
        }

        // Initialize profile metadata file
        profileMetadataFile = File(voiceProfileDir, "profile_metadata.json")
        if (!profileMetadataFile.exists()) {
            try {
                state.initializeEmptyProfile(profileMetadataFile)
                Log.i(VoiceTrainingState.TAG, "Initialized empty profile at ${profileMetadataFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error initializing empty profile: ${e.message}")
                e.printStackTrace()
            }
        } else {
            Log.i(VoiceTrainingState.TAG, "Profile metadata exists at ${profileMetadataFile.absolutePath}")
            try {
                val metadata = readMetadata()
                val profile = metadata.getJSONObject("profile")
                val samples = profile.optJSONArray("samples") ?: JSONArray()
                Log.d(VoiceTrainingState.TAG, "Existing profile has ${samples.length()} samples")
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error reading existing profile: ${e.message}")
                e.printStackTrace()
                // If profile is corrupted, reinitialize it
                state.initializeEmptyProfile(profileMetadataFile)
            }
        }
    }

    fun getVoiceProfileDir(): File {
        return voiceProfileDir
    }

    private fun readMetadata(): JSONObject {
        return metadataLock.withLock {
            try {
                val content = profileMetadataFile.readText()
                Log.d(VoiceTrainingState.TAG, "Read metadata content: $content")
                JSONObject(content)
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error reading metadata: ${e.message}")
                throw e
            }
        }
    }

    private fun writeMetadata(metadata: JSONObject) {
        metadataLock.withLock {
            try {
                val tempFile = File(profileMetadataFile.parentFile, "profile_metadata.tmp")
                val content = metadata.toString(2)
                tempFile.writeText(content)
                val success = tempFile.renameTo(profileMetadataFile)
                Log.d(VoiceTrainingState.TAG, "Wrote metadata content: $content, success: $success")
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error writing metadata: ${e.message}")
                throw e
            }
        }
    }

    fun getNextPhraseIndex(): Int {
        try {
            val metadata = readMetadata()
            val profile = metadata.getJSONObject("profile")
            val index = profile.optInt("lastPhraseIndex", 0)
            Log.d(VoiceTrainingState.TAG, "Got next phrase index: $index")
            return index
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error getting next phrase index: ${e.message}")
            return 0
        }
    }

    fun updatePhraseIndex(currentIndex: Int) {
        try {
            val metadata = readMetadata()
            val profile = metadata.getJSONObject("profile")
            val newIndex = (currentIndex + 1) % VoiceTrainingState.TRAINING_PHRASES.size
            profile.put("lastPhraseIndex", newIndex)
            metadata.put("profile", profile)
            writeMetadata(metadata)
            Log.d(VoiceTrainingState.TAG, "Updated phrase index from $currentIndex to $newIndex")
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error updating phrase index: ${e.message}")
        }
    }

    fun updateProfileMetadata(sampleFile: File, characteristics: JSONObject) {
        metadataLock.withLock {
            try {
                val metadata = readMetadata()
                val profile = metadata.getJSONObject("profile")
                val samples = profile.optJSONArray("samples") ?: JSONArray()
                val profileCharacteristics = profile.optJSONObject("characteristics") ?: JSONObject()
                
                Log.d(VoiceTrainingState.TAG, "Current samples count: ${samples.length()}")
                
                // Validate sample count
                if (samples.length() >= VoiceTrainingState.MIN_PROFILE_SAMPLES) {
                    Log.w(VoiceTrainingState.TAG, "Maximum number of samples (${VoiceTrainingState.MIN_PROFILE_SAMPLES}) already reached")
                    return
                }
                
                // Update samples list
                samples.put(JSONObject().apply {
                    put("file", sampleFile.name)
                    put("characteristics", characteristics)
                })
                
                Log.i(VoiceTrainingState.TAG, "Added new voice sample. Current count: ${samples.length()}/${VoiceTrainingState.MIN_PROFILE_SAMPLES}")
                
                // Update average profile characteristics
                if (samples.length() > 0) {
                    var avgRMSEnergy = 0.0
                    var avgZeroCrossingRate = 0.0
                    val volumeEnvelopeLength = characteristics.getJSONArray("volumeEnvelope").length()
                    val avgVolumeEnvelope = DoubleArray(volumeEnvelopeLength)
                    
                    for (i in 0 until samples.length()) {
                        val sample = samples.getJSONObject(i)
                        val sampleChar = sample.getJSONObject("characteristics")
                        
                        avgRMSEnergy += sampleChar.optDouble("rmsEnergy", 0.0)
                        avgZeroCrossingRate += sampleChar.optDouble("zeroCrossingRate", 0.0)
                        
                        val sampleEnvelope = sampleChar.getJSONArray("volumeEnvelope")
                        for (j in 0 until volumeEnvelopeLength) {
                            avgVolumeEnvelope[j] += sampleEnvelope.optDouble(j, 0.0)
                        }
                    }
                    
                    // Calculate averages
                    val count = samples.length().toDouble()
                    avgRMSEnergy /= count
                    avgZeroCrossingRate /= count
                    for (i in avgVolumeEnvelope.indices) {
                        avgVolumeEnvelope[i] /= count
                    }
                    
                    // Update profile characteristics
                    profileCharacteristics.put("rmsEnergy", avgRMSEnergy)
                    profileCharacteristics.put("zeroCrossingRate", avgZeroCrossingRate)
                    profileCharacteristics.put("volumeEnvelope", JSONArray(avgVolumeEnvelope.toList()))
                    Log.d(VoiceTrainingState.TAG, "Updated profile characteristics: $profileCharacteristics")
                }
                
                profile.put("samples", samples)
                profile.put("characteristics", profileCharacteristics)
                
                // Update trained status only when exactly MIN_PROFILE_SAMPLES are collected
                val currentTrained = profile.optBoolean("trained", false)
                if (samples.length() == VoiceTrainingState.MIN_PROFILE_SAMPLES && !currentTrained) {
                    profile.put("trained", true)
                    Log.i(VoiceTrainingState.TAG, "Voice profile training completed with ${samples.length()} samples")
                } else if (samples.length() < VoiceTrainingState.MIN_PROFILE_SAMPLES && currentTrained) {
                    profile.put("trained", false)
                    Log.w(VoiceTrainingState.TAG, "Profile marked as untrained - insufficient samples")
                }
                
                profile.put("lastUpdated", System.currentTimeMillis())
                metadata.put("profile", profile)
                
                // Write updated metadata back to file
                writeMetadata(metadata)
                
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error updating profile metadata: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun getProfileStatus(): JSONObject {
        try {
            val metadata = readMetadata()
            val profile = metadata.getJSONObject("profile")
            val samples = profile.optJSONArray("samples") ?: JSONArray()
            val sampleCount = samples.length()
            
            // Ensure trained status is accurate based on sample count
            val shouldBeTrained = sampleCount == VoiceTrainingState.MIN_PROFILE_SAMPLES
            val currentlyTrained = profile.optBoolean("trained", false)
            
            if (shouldBeTrained != currentlyTrained) {
                profile.put("trained", shouldBeTrained)
                metadata.put("profile", profile)
                writeMetadata(metadata)
                Log.i(VoiceTrainingState.TAG, "Corrected profile trained status to $shouldBeTrained")
            }
            
            val status = JSONObject().apply {
                put("sampleCount", sampleCount)
                put("trained", shouldBeTrained)
                put("requiredSamples", VoiceTrainingState.MIN_PROFILE_SAMPLES)
            }
            Log.d(VoiceTrainingState.TAG, "Profile status: $status")
            return status
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error getting profile status: ${e.message}")
            return JSONObject().apply {
                put("sampleCount", 0)
                put("trained", false)
                put("requiredSamples", VoiceTrainingState.MIN_PROFILE_SAMPLES)
            }
        }
    }

    fun resetProfile() {
        metadataLock.withLock {
            try {
                var filesDeleted = 0
                voiceProfileDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("profile_sample_")) {
                        val success = file.delete()
                        if (success) filesDeleted++
                    }
                }
                Log.i(VoiceTrainingState.TAG, "Deleted $filesDeleted sample files")
                
                state.initializeEmptyProfile(profileMetadataFile)
                Log.i(VoiceTrainingState.TAG, "Voice profile reset successfully")
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error resetting profile: ${e.message}")
            }
        }
    }

    fun isProfileTrained(): Boolean {
        try {
            val metadata = readMetadata()
            val profile = metadata.getJSONObject("profile")
            val samples = profile.optJSONArray("samples") ?: JSONArray()
            val trained = samples.length() == VoiceTrainingState.MIN_PROFILE_SAMPLES
            Log.d(VoiceTrainingState.TAG, "Profile trained status: $trained (${samples.length()} samples)")
            return trained
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error checking profile status: ${e.message}")
            return false
        }
    }

    fun getProfileCharacteristics(): JSONObject? {
        try {
            val metadata = readMetadata()
            val characteristics = metadata.getJSONObject("profile").optJSONObject("characteristics")
            Log.d(VoiceTrainingState.TAG, "Got profile characteristics: $characteristics")
            return characteristics
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error getting profile characteristics: ${e.message}")
            return null
        }
    }
}
