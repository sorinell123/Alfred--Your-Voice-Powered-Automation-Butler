package com.example.alfred

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.example.alfred.voice.VoiceCommandService
import android.content.BroadcastReceiver
import android.content.IntentFilter

class InputService : AccessibilityService() {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private lateinit var actionRecorder: ActionRecorder
    private lateinit var actionPlayer: ActionPlayer
    private var voiceCommandService: VoiceCommandService? = null

    var isRecording = false
        private set

    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.alfred.TOGGLE_RECORDING") {
                Log.d("InputService", "Received toggle recording broadcast")
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
        }
    }

    companion object {
        private var instance: InputService? = null
        fun getInstance(): InputService? = instance

        fun isServiceEnabled(context: Context): Boolean {
            val accessibilityEnabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                )
            } catch (e: Settings.SettingNotFoundException) {
                0
            }

            if (accessibilityEnabled == 1) {
                val serviceString = "${context.packageName}/${context.packageName}.InputService"
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                return enabledServices?.contains(serviceString) == true
            }
            return false
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("InputService", "InputService onCreate called")
        instance = this
        // Initialize with null VoiceCommandService, will be set later
        actionRecorder = ActionRecorder(null)
        actionPlayer = ActionPlayer(this, scope, null)
        
        // Register for TOGGLE_RECORDING broadcasts
        val filter = IntentFilter("com.example.alfred.TOGGLE_RECORDING")
        registerReceiver(toggleReceiver, filter)
        
        Log.d("InputService", "InputService initialization completed")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("InputService", "InputService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        instance = null
        // Unregister the receiver
        try {
            unregisterReceiver(toggleReceiver)
        } catch (e: Exception) {
            Log.e("InputService", "Error unregistering receiver: ${e.message}")
        }
        Log.d("InputService", "InputService destroyed")
    }

    override fun onInterrupt() {
        Log.d("InputService", "Service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (isRecording && event != null) {
            actionRecorder.recordEvent(event)
        }
    }

    fun setVoiceCommandService(service: VoiceCommandService) {
        Log.d("InputService", "Setting VoiceCommandService")
        voiceCommandService = service
        // Reinitialize with the voice command service
        actionRecorder = ActionRecorder(service)
        actionPlayer = ActionPlayer(this, scope, service)
    }

    fun startRecording() {
        if (!isRecording) {
            isRecording = true
            actionRecorder.startRecording()
            Log.d("InputService", "Recording started")
            notifyRecordingStateChanged(true)
        }
    }

    fun stopRecording(): List<Map<String, Any>> {
        if (isRecording) {
            isRecording = false
            val actions = actionRecorder.stopRecording()
            Log.d("InputService", "Recording stopped. Actions recorded: ${actions.size}")
            notifyRecordingStateChanged(false)
            return actions
        }
        return emptyList()
    }

    fun playbackActions(actions: List<Map<String, Any>>, speed: Float = 1.0f) {
        actionPlayer.playbackActions(actions, speed)
    }

    fun setPlaybackSpeed(speed: Float) {
        actionPlayer.setPlaybackSpeed(speed)
    }

    fun getActionPlayer(): ActionPlayer {
        return actionPlayer
    }

    private fun notifyRecordingStateChanged(isRecording: Boolean) {
        val intent = Intent("com.example.alfred.RECORDING_STATE_CHANGED")
        intent.putExtra("isRecording", isRecording)
        sendBroadcast(intent)
    }
}
