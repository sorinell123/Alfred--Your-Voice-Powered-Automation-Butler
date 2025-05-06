package com.example.alfred.voice

import android.util.Log
import kotlin.math.min

class CommandMatcher {
    fun findBestMatch(spokenCommand: String, availableCommands: List<String>): String? {
        if (spokenCommand.isEmpty() || availableCommands.isEmpty()) {
            return null
        }
        
        var bestMatch: String? = null
        var bestScore = 0.0

        for (command in availableCommands) {
            val similarity = calculateCommandSimilarity(spokenCommand.toLowerCase(), command.toLowerCase())
            Log.d(VoiceCommandState.TAG, "Similarity between '$spokenCommand' and '$command': $similarity")
            if (similarity > bestScore && similarity >= VoiceCommandState.SIMILARITY_THRESHOLD) {
                bestScore = similarity
                bestMatch = command
            }
        }

        return bestMatch
    }

    fun calculateCommandSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1 == s2) return 1.0
        if (s1.contains(s2) || s2.contains(s1)) return 0.9

        val words1 = s1.split(" ")
        val words2 = s2.split(" ")

        var matches = 0
        for (w1 in words1) {
            for (w2 in words2) {
                if (w1 == w2 || levenshteinDistance(w1, w2) <= 2) {
                    matches++
                    break
                }
            }
        }

        val wordSimilarity = matches.toDouble() / words1.size
        val lengthRatio = min(s1.length.toDouble() / s2.length, s2.length.toDouble() / s1.length)

        return (wordSimilarity * 0.7 + lengthRatio * 0.3)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                dp[i][j] = if (s1[i-1] == s2[j-1]) {
                    dp[i-1][j-1]
                } else {
                    min(dp[i-1][j], min(dp[i][j-1], dp[i-1][j-1])) + 1
                }
            }
        }

        return dp[s1.length][s2.length]
    }
}
