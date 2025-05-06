package com.example.alfred

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import android.util.Log
import android.view.WindowManager
import com.example.alfred.voice.VoiceCommandService
import com.example.alfred.voice.VoiceTrainingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.annotation.NonNull
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

class MainActivity: FlutterActivity() {
    private val RECORDER_CHANNEL = "com.example.alfred/recorder"
    private val ACCESSIBILITY_CHANNEL = "com.example.alfred/accessibility"
    private val FLOATING_BUTTON_CHANNEL = "com.example.alfred/floating_button"
    private val VOICE_CHANNEL = "com.example.alfred/voice"
    private val VOICE_TRAINING_CHANNEL = "com.example.alfred/voice_training"
    private val PLAYBACK_CHANNEL = "com.example.alfred/playback"
    private val PERMISSION_REQUEST_CODE = 123
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var voiceCommandService: VoiceCommandService
    private val voiceTrainingService by lazy { 
        VoiceTrainingService.getInstance(context)
    }
    private lateinit var floatingButtonService: FloatingButtonService
    private var pendingOperation: (() -> Unit)? = null
    private lateinit var recorderChannel: MethodChannel

    // Add coroutine scope
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val recordingButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.alfred.TRIGGER_RECORDING_BUTTON") {
                Log.d("MainActivity", "Received TRIGGER_RECORDING_BUTTON broadcast")
                mainHandler.post {
                    // Simply trigger Flutter's _toggleRecording()
                    recorderChannel.invokeMethod("toggleRecording", null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on during voice training
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Register for recording button broadcasts
        val filter = IntentFilter("com.example.alfred.TRIGGER_RECORDING_BUTTON")
        registerReceiver(recordingButtonReceiver, filter)
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize floating button service
        floatingButtonService = FloatingButtonService()
        
        // Initialize voice command service with method channel
        val voiceChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VOICE_CHANNEL)
        voiceCommandService = VoiceCommandService(context)
        voiceCommandService.initialize(voiceChannel)

        // Initialize voice training service with method channel
        val voiceTrainingChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VOICE_TRAINING_CHANNEL)
        voiceTrainingService.initialize(voiceTrainingChannel)

        // Pass VoiceCommandService to InputService if it's running
        InputService.getInstance()?.let { service ->
            Log.d("MainActivity", "Passing VoiceCommandService to InputService")
            service.setVoiceCommandService(voiceCommandService)
        }

        // Initialize recorder channel
        recorderChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, RECORDER_CHANNEL)
        recorderChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startRecording" -> {
                    try {
                        val inputService = InputService.getInstance()
                        if (inputService != null) {
                            inputService.startRecording()
                            result.success(true)
                        } else {
                            result.error("SERVICE_NOT_RUNNING", "Input service is not running", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error starting recording: ${e.message}")
                        result.error("RECORDING_ERROR", "Failed to start recording: ${e.message}", null)
                    }
                }
                "stopRecording" -> {
                    try {
                        val inputService = InputService.getInstance()
                        if (inputService != null) {
                            val actions = inputService.stopRecording()
                            // Convert the actions to a List<Map<String, Any>> that Flutter can understand
                            val serializedActions = actions.map { action ->
                                action.mapValues { (_, value) ->
                                    when (value) {
                                        is Int -> value
                                        is Long -> value
                                        is Float -> value
                                        is Double -> value
                                        is Boolean -> value
                                        is String -> value
                                        else -> value.toString()
                                    }
                                }
                            }
                            result.success(serializedActions)
                        } else {
                            result.error("SERVICE_NOT_RUNNING", "Input service is not running", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error stopping recording: ${e.message}")
                        result.error("RECORDING_ERROR", "Failed to stop recording: ${e.message}", null)
                    }
                }
                "showSaveMacroDialog" -> {
                    showSaveMacroDialog { macroName ->
                        result.success(macroName)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // Voice Command Channel
        voiceChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startListening" -> {
                    if (checkAndRequestPermissions {
                        scope.launch {
                            voiceCommandService.startListening()
                            result.success(true)
                        }
                    }) {
                        scope.launch {
                            voiceCommandService.startListening()
                            result.success(true)
                        }
                    } else {
                        result.error("PERMISSION_DENIED", "Required permissions not granted", null)
                    }
                }
                "stopListening" -> {
                    scope.launch {
                        voiceCommandService.stopListening()
                        result.success(true)
                    }
                }
                "updateCommands" -> {
                    val commands = call.argument<List<String>>("commands")
                    if (commands != null) {
                        scope.launch {
                            voiceCommandService.updateAvailableCommands(commands)
                            result.success(true)
                        }
                    } else {
                        result.error("INVALID_ARGUMENT", "Commands list is required", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // Voice Training Channel
        voiceTrainingChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "getCurrentTrainingPhrase" -> {
                    scope.launch {
                        val phrase = voiceTrainingService.getCurrentTrainingPhrase()
                        result.success(phrase)
                    }
                }
                "startProfileTraining" -> {
                    if (checkAndRequestPermissions {
                        scope.launch {
                            val success = voiceTrainingService.startProfileTraining()
                            result.success(success)
                        }
                    }) {
                        scope.launch {
                            val success = voiceTrainingService.startProfileTraining()
                            result.success(success)
                        }
                    } else {
                        result.error("PERMISSION_DENIED", "Required permissions not granted", null)
                    }
                }
                "stopRecording" -> {
                    scope.launch {
                        val success = voiceTrainingService.stopRecording()
                        result.success(success)
                    }
                }
                "getProfileStatus" -> {
                    try {
                        val status = voiceTrainingService.getProfileStatus()
                        // Convert JSONObject to Map for Flutter
                        val map = mapOf(
                            "sampleCount" to status.optInt("sampleCount", 0),
                            "trained" to status.optBoolean("trained", false),
                            "requiredSamples" to status.optInt("requiredSamples", 5)
                        )
                        result.success(map)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error getting profile status: ${e.message}")
                        result.success(mapOf(
                            "sampleCount" to 0,
                            "trained" to false,
                            "requiredSamples" to 5
                        ))
                    }
                }
                "resetProfile" -> {
                    scope.launch {
                        val success = voiceTrainingService.resetProfile()
                        result.success(success)
                    }
                }
                "isProfileTrained" -> {
                    val trained = voiceTrainingService.isProfileTrained()
                    result.success(trained)
                }
                else -> result.notImplemented()
            }
        }

        // Accessibility Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, ACCESSIBILITY_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isAccessibilityServiceEnabled" -> {
                    val enabled = InputService.isServiceEnabled(context)
                    result.success(enabled)
                }
                "openAccessibilitySettings" -> {
                    openAccessibilitySettings()
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        // Floating Button Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FLOATING_BUTTON_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "showFloatingButton" -> {
                    val intent = Intent(this, FloatingButtonService::class.java)
                    startService(intent)
                    result.success(true)
                }
                "hideFloatingButton" -> {
                    val intent = Intent(this, FloatingButtonService::class.java)
                    stopService(intent)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        // Playback Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PLAYBACK_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "playbackActions" -> {
                    try {
                        val actions = call.argument<List<Map<String, Any>>>("actions")
                        val speed = call.argument<Double>("speed")?.toFloat() ?: 1.0f
                        
                        if (actions != null) {
                            val inputService = InputService.getInstance()
                            if (inputService != null) {
                                scope.launch {
                                    try {
                                        inputService.getActionPlayer().playbackActions(actions, speed)
                                        result.success(true)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error during playback: ${e.message}")
                                        result.error("PLAYBACK_ERROR", "Failed to play actions: ${e.message}", null)
                                    }
                                }
                            } else {
                                result.error("SERVICE_NOT_RUNNING", "Input service is not running", null)
                            }
                        } else {
                            result.error("INVALID_ARGUMENT", "Actions list is required", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error in playbackActions: ${e.message}")
                        result.error("PLAYBACK_ERROR", "Failed to process playback request: ${e.message}", null)
                    }
                }
                "setPlaybackSpeed" -> {
                    try {
                        val speed = call.argument<Double>("speed")?.toFloat()
                        if (speed != null) {
                            InputService.getInstance()?.getActionPlayer()?.setPlaybackSpeed(speed)
                            result.success(true)
                        } else {
                            result.error("INVALID_ARGUMENT", "Speed value is required", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error setting playback speed: ${e.message}")
                        result.error("PLAYBACK_ERROR", "Failed to set playback speed: ${e.message}", null)
                    }
                }
                "stopPlayback" -> {
                    try {
                        InputService.getInstance()?.getActionPlayer()?.pause()
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error stopping playback: ${e.message}")
                        result.error("PLAYBACK_ERROR", "Failed to stop playback: ${e.message}", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun checkAndRequestPermissions(operation: () -> Unit): Boolean {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val notGrantedPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            pendingOperation = operation
            ActivityCompat.requestPermissions(
                this,
                notGrantedPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            return false
        }

        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("MainActivity", "All required permissions granted")
                pendingOperation?.invoke()
            } else {
                Log.e("MainActivity", "Some permissions were denied")
                showPermissionDeniedDialog()
            }
            pendingOperation = null
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Voice recognition requires microphone and storage permissions to function properly. Please grant these permissions in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSaveMacroDialog(callback: (String?) -> Unit) {
        val builder = AlertDialog.Builder(this)
        val input = EditText(this)
        val dialog = builder.setTitle("Save Macro")
            .setMessage("Enter a name for this macro:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                callback(input.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ ->
                callback(null)
            }
            .create()

        // Set the dialog to appear on top of other apps
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        
        dialog.show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voiceCommandService.destroy()
        scope.launch {
            voiceCommandService.cleanup()
        }
        try {
            unregisterReceiver(recordingButtonReceiver)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error unregistering receiver: ${e.message}")
        }
    }
}
