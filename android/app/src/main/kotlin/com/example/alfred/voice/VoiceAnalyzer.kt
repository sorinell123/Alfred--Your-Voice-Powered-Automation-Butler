package com.example.alfred.voice

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class VoiceAnalyzer {
    companion object {
        private const val ENERGY_BANDS = 8
        private const val ANALYSIS_WINDOW_SIZE = 1024 // Reduced window size for faster processing
        private const val SIMILARITY_THRESHOLD = 0.65
        private const val MIN_VALID_SAMPLES = 100 // Significantly reduced for initial testing
        private const val MAX_AMPLITUDE_VARIANCE = 0.3
        private const val MAX_ZCR_VARIANCE = 0.4
        private const val CHUNK_SIZE = 8192 // Process audio in smaller chunks
    }

    fun extractVoiceCharacteristics(audioFile: File): JSONObject {
        val extractor = MediaExtractor()
        val characteristics = JSONObject()
        
        try {
            Log.d(VoiceTrainingState.TAG, "Starting voice characteristics extraction from: ${audioFile.absolutePath}")
            if (!audioFile.exists()) {
                throw IllegalStateException("Audio file does not exist: ${audioFile.absolutePath}")
            }
            if (audioFile.length() == 0L) {
                throw IllegalStateException("Audio file is empty: ${audioFile.absolutePath}")
            }

            extractor.setDataSource(audioFile.absolutePath)
            if (extractor.trackCount == 0) {
                throw IllegalStateException("No audio tracks found in file")
            }

            val format = extractor.getTrackFormat(0)
            Log.d(VoiceTrainingState.TAG, "Audio format: $format")
            
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val duration = format.getLong(MediaFormat.KEY_DURATION)
            val mimeType = format.getString(MediaFormat.KEY_MIME)
            
            // Read and process audio data in chunks
            val samples = readAudioSamplesInChunks(extractor, format)
            Log.d(VoiceTrainingState.TAG, "Read ${samples.size} audio samples")
            
            if (samples.isEmpty()) {
                throw IllegalStateException("No audio samples read from file")
            }
            
            // Check if we have enough samples for basic analysis
            if (samples.size < MIN_VALID_SAMPLES) {
                Log.w(VoiceTrainingState.TAG, "Warning: Low sample count (${samples.size}), but continuing with analysis")
            }
            
            // Calculate max amplitude with more lenient threshold
            val maxAmplitude = samples.maxOf { abs(it) }
            if (maxAmplitude < VoiceTrainingState.MIN_AMPLITUDE_THRESHOLD) {
                Log.w(VoiceTrainingState.TAG, "Warning: Low amplitude ($maxAmplitude), but continuing with analysis")
            }
            
            // Calculate signal-to-noise ratio with more tolerance
            val signalPower = samples.map { it * it }.average()
            val noisePower = samples.filter { abs(it) < maxAmplitude * 0.1 }.map { it * it }.average()
            val snr = if (noisePower > 0) 10 * log10(signalPower / noisePower) else 0.0
            
            if (snr < VoiceTrainingState.MIN_SIGNAL_NOISE_RATIO) {
                Log.w(VoiceTrainingState.TAG, "Warning: Low SNR ($snr), but continuing with analysis")
            }
            
            // Normalize samples
            val normalizedSamples = normalizeSamples(samples)
            
            // Calculate characteristics
            val rmsEnergy = calculateRMSEnergy(normalizedSamples)
            val zeroCrossingRate = calculateZeroCrossingRate(normalizedSamples)
            val volumeEnvelope = calculateVolumeEnvelope(normalizedSamples)
            
            characteristics.put("rmsEnergy", rmsEnergy)
            characteristics.put("zeroCrossingRate", zeroCrossingRate)
            characteristics.put("volumeEnvelope", JSONArray(volumeEnvelope.toList()))
            characteristics.put("duration", duration)
            characteristics.put("sampleRate", sampleRate)
            characteristics.put("channelCount", channelCount)
            characteristics.put("maxAmplitude", maxAmplitude)
            characteristics.put("signalToNoiseRatio", snr)
            characteristics.put("mimeType", mimeType)
            
            Log.i(VoiceTrainingState.TAG, "Successfully extracted voice characteristics: $characteristics")
            
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error extracting voice characteristics: ${e.message}")
            e.printStackTrace()
            throw e
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.e(VoiceTrainingState.TAG, "Error releasing MediaExtractor: ${e.message}")
            }
        }
        
        return characteristics
    }

    private fun readAudioSamplesInChunks(extractor: MediaExtractor, format: MediaFormat): DoubleArray {
        val samples = mutableListOf<Double>()
        val buffer = ByteBuffer.allocate(CHUNK_SIZE)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        try {
            extractor.selectTrack(0)
            var totalBytesRead = 0
            
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                
                totalBytesRead += sampleSize
                
                // Process this chunk of samples
                for (i in 0 until sampleSize step 2) {
                    if (i + 1 < sampleSize) {
                        val sample = buffer.getShort(i).toDouble()
                        samples.add(sample)
                        
                        // Add basic progress logging
                        if (samples.size % 10000 == 0) {
                            Log.d(VoiceTrainingState.TAG, "Processed ${samples.size} samples...")
                        }
                    }
                }
                
                if (!extractor.advance()) break
                
                // Optional: limit total samples for very long recordings
                if (samples.size > 100000) { // About 6 seconds at 16kHz
                    Log.w(VoiceTrainingState.TAG, "Reached sample limit, truncating recording")
                    break
                }
            }
            
            Log.d(VoiceTrainingState.TAG, "Total bytes read: $totalBytesRead")
            
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error reading audio samples: ${e.message}")
            // Don't throw here - return whatever samples we managed to read
            if (samples.isEmpty()) {
                throw e // Only throw if we got no samples at all
            }
        }
        
        return samples.toDoubleArray()
    }

    private fun normalizeSamples(samples: DoubleArray): DoubleArray {
        val maxAbs = samples.maxOf { abs(it) }
        return if (maxAbs > 0.0) {
            DoubleArray(samples.size) { i -> samples[i] / maxAbs }
        } else {
            samples.clone() // Return copy of original if can't normalize
        }
    }

    private fun calculateRMSEnergy(samples: DoubleArray): Double {
        if (samples.isEmpty()) return 0.0
        
        // Use a more lenient threshold for noise reduction
        val threshold = 0.05
        val significantSamples = samples.filter { abs(it) > threshold }
        if (significantSamples.isEmpty()) return 0.0
        
        val sum = significantSamples.sumOf { it * it }
        return sqrt(sum / significantSamples.size)
    }

    private fun calculateZeroCrossingRate(samples: DoubleArray): Double {
        if (samples.size < 2) return 0.0
        
        // More lenient hysteresis
        val hysteresis = 0.01
        var crossings = 0
        for (i in 1 until samples.size) {
            if (samples[i-1] < -hysteresis && samples[i] > hysteresis ||
                samples[i-1] > hysteresis && samples[i] < -hysteresis) {
                crossings++
            }
        }
        
        return crossings.toDouble() / (samples.size - 1)
    }

    private fun calculateVolumeEnvelope(samples: DoubleArray): DoubleArray {
        if (samples.isEmpty()) return DoubleArray(ENERGY_BANDS)
        
        val envelope = DoubleArray(ENERGY_BANDS)
        val samplesPerBand = samples.size / ENERGY_BANDS
        
        for (i in 0 until ENERGY_BANDS) {
            val startIndex = i * samplesPerBand
            val endIndex = if (i == ENERGY_BANDS - 1) samples.size else (i + 1) * samplesPerBand
            
            if (startIndex < samples.size) {
                val bandSamples = samples.slice(startIndex until minOf(endIndex, samples.size))
                envelope[i] = calculateRMSEnergy(bandSamples.toDoubleArray())
            }
        }
        
        // Normalize envelope
        val maxEnergy = envelope.maxOrNull() ?: 0.0
        if (maxEnergy > 0.0) {
            for (i in envelope.indices) {
                envelope[i] /= maxEnergy
            }
        }
        
        return envelope
    }

    fun matchVoiceProfile(audioData: ByteArray, profileCharacteristics: JSONObject): Boolean {
        try {
            val samples = convertAudioDataToSamples(audioData)
            if (samples.isEmpty()) {
                Log.w(VoiceTrainingState.TAG, "No audio samples for matching")
                return false
            }
            
            val normalizedSamples = normalizeSamples(samples)
            
            // Calculate current characteristics
            val rmsEnergy = calculateRMSEnergy(normalizedSamples)
            val zeroCrossingRate = calculateZeroCrossingRate(normalizedSamples)
            val volumeEnvelope = calculateVolumeEnvelope(normalizedSamples)
            
            // Compare with profile characteristics
            val similarity = calculateProfileSimilarity(
                rmsEnergy,
                zeroCrossingRate,
                volumeEnvelope,
                profileCharacteristics
            )
            
            Log.i(VoiceTrainingState.TAG, "Voice profile match similarity: $similarity (threshold: $SIMILARITY_THRESHOLD)")
            return similarity >= SIMILARITY_THRESHOLD
            
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error matching voice profile: ${e.message}")
            return false
        }
    }

    private fun convertAudioDataToSamples(audioData: ByteArray): DoubleArray {
        if (audioData.isEmpty()) return DoubleArray(0)
        
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        val samples = DoubleArray(audioData.size / 2)
        
        try {
            for (i in samples.indices) {
                samples[i] = buffer.getShort().toDouble()
            }
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error converting audio data: ${e.message}")
            // Return whatever samples we managed to convert
            return samples.copyOf(minOf(samples.size, buffer.position() / 2))
        }
        
        return samples
    }

    private fun calculateProfileSimilarity(
        rmsEnergy: Double,
        zeroCrossingRate: Double,
        volumeEnvelope: DoubleArray,
        profileCharacteristics: JSONObject
    ): Double {
        var similarity = 0.0
        var weightSum = 0.0
        
        try {
            // Compare RMS energy
            val profileRMS = profileCharacteristics.optDouble("rmsEnergy", 0.0)
            val energyDiff = abs(rmsEnergy - profileRMS) / maxOf(rmsEnergy, profileRMS, 0.0001)
            if (energyDiff <= MAX_AMPLITUDE_VARIANCE) {
                val energySimilarity = 1.0 - (energyDiff / MAX_AMPLITUDE_VARIANCE)
                similarity += 0.3 * energySimilarity
                weightSum += 0.3
            }
            
            // Compare zero-crossing rate
            val profileZCR = profileCharacteristics.optDouble("zeroCrossingRate", 0.0)
            val zcrDiff = abs(zeroCrossingRate - profileZCR) / maxOf(zeroCrossingRate, profileZCR, 0.0001)
            if (zcrDiff <= MAX_ZCR_VARIANCE) {
                val zcrSimilarity = 1.0 - (zcrDiff / MAX_ZCR_VARIANCE)
                similarity += 0.3 * zcrSimilarity
                weightSum += 0.3
            }
            
            // Compare volume envelope
            val profileEnvelope = profileCharacteristics.optJSONArray("volumeEnvelope")
            if (profileEnvelope != null) {
                var envelopeSimilarity = 0.0
                var validBands = 0
                
                for (i in volumeEnvelope.indices) {
                    if (i < profileEnvelope.length()) {
                        val profileValue = profileEnvelope.optDouble(i, 0.0)
                        val diff = abs(volumeEnvelope[i] - profileValue)
                        if (diff <= 0.3) {
                            envelopeSimilarity += 1.0 - (diff / 0.3)
                            validBands++
                        }
                    }
                }
                
                if (validBands > 0) {
                    envelopeSimilarity /= validBands
                    similarity += 0.4 * envelopeSimilarity
                    weightSum += 0.4
                }
            }
            
        } catch (e: Exception) {
            Log.e(VoiceTrainingState.TAG, "Error calculating profile similarity: ${e.message}")
            // Return partial similarity if we have any
            return if (weightSum > 0.0) similarity / weightSum else 0.0
        }
        
        return if (weightSum > 0.0) similarity / weightSum else 0.0
    }
}
