package com.example.alfred

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import com.example.alfred.voice.VoiceCommandService

@RequiresApi(Build.VERSION_CODES.N)
class ActionPlayer(
    service: AccessibilityService,
    scope: CoroutineScope,
    private val voiceCommandService: VoiceCommandService? = null
) : ActionPlayerBase(service, scope) {

    private var isHandlingCall: Boolean = false
    private var currentActions: List<Map<String, Any>> = emptyList()
    private var currentActionIndex: Int = 0
    private var lastWindowId: Int = -1

    private val messageHandler = MessageHandler(service, scope)
    private val callHandler = CallHandler(service, scope)

    fun playbackActions(actions: List<Map<String, Any>>, speed: Float = 1.0f) {
        speedMultiplier = speed
        messageHandler.setPlaybackSpeed(speed)
        callHandler.setPlaybackSpeed(speed)

        currentActions = actions
        currentActionIndex = 0

        // Pause voice commands during playback
        voiceCommandService?.pause()

        scope.launch {
            try {
                while (currentActionIndex < currentActions.size) {
                    val action = currentActions[currentActionIndex]
                    Log.d("ActionPlayer", "Playing back action ${currentActionIndex + 1} of ${currentActions.size}")

                    if (shouldSkipAction(action)) {
                        Log.d("ActionPlayer", "Skipping system app switch during call handling")
                        currentActionIndex++
                        continue
                    }

                    when (action["type"] as? String) {
                        "click" -> handleClick(action)
                        "text_change" -> handleTextChange(action)
                        "app_switch" -> handleAppSwitch(action)
                        else -> Log.w("ActionPlayer", "Unknown action type for action $currentActionIndex")
                    }

                    currentActionIndex++
                    delay((500 / speedMultiplier).toLong())
                }
            } finally {
                Log.d("ActionPlayer", "Playback completed")
                messageHandler.reset()
                isHandlingCall = false
                currentActions = emptyList()
                currentActionIndex = 0
                lastWindowId = -1
                // Resume voice commands after playback
                voiceCommandService?.resume()
            }
        }
    }

    private suspend fun handleClick(action: Map<String, Any>) {
        val packageName = action["packageName"] as? String
        val isSimSelection = action["isSimSelection"] as? Boolean ?: false

        when {
            isSimSelection -> {
                Log.d("ActionPlayer", "Handling SIM selection action")
                callHandler.handleSimSelection(action)
            }
            isDialerPackage(packageName) -> {
                isHandlingCall = true
                callHandler.handleCallAction(action, currentActionIndex, currentActions)
            }
            else -> {
                performBasicClick(action)
            }
        }
    }

    private suspend fun handleTextChange(action: Map<String, Any>) {
        messageHandler.handleTextEntry(
            text = action["text"] as? String ?: return,
            packageName = action["packageName"] as? String,
            isCompose = action["isCompose"] as? Boolean ?: false
        )
    }

    private suspend fun handleAppSwitch(action: Map<String, Any>) {
        performAppSwitch(action["packageName"] as? String)
    }

    private fun isDialerPackage(packageName: String?): Boolean {
        return packageName == "com.samsung.android.dialer" ||
                packageName?.contains("dialer") == true ||
                packageName == "com.android.server.telecom"
    }

    private fun shouldSkipAction(action: Map<String, Any>): Boolean {
        if (!isHandlingCall) {
            return false
        }

        val packageName = action["packageName"] as? String
        return when {
            action["type"] == "app_switch" && (
                    packageName == "com.samsung.android.incallui" ||
                            packageName == "com.android.server.telecom" ||
                            packageName?.contains("telecom") == true ||
                            packageName?.contains("incallui") == true
                    ) -> true
            else -> false
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        super.setPlaybackSpeed(speed)
        messageHandler.setPlaybackSpeed(speed)
        callHandler.setPlaybackSpeed(speed)
    }

    override fun pause() {
        super.pause()
    }

    override fun resume() {
        super.resume()
    }
}
