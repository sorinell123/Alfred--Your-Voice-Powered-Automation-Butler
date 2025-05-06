package com.example.alfred

import android.view.accessibility.AccessibilityEvent
import android.util.Log
import com.example.alfred.voice.VoiceCommandService

class ActionRecorder(private val voiceCommandService: VoiceCommandService? = null) {
    private var _isRecording = false
    private val recordedActions = mutableListOf<Map<String, Any>>()
    private var isPaused = false

    val isRecording: Boolean
        get() = _isRecording

    fun startRecording() {
        if (!_isRecording) {
            _isRecording = true
            recordedActions.clear()
            // Pause voice commands while recording
            voiceCommandService?.pause()
            Log.d("ActionRecorder", "Recording started")
        }
    }

    fun stopRecording(): List<Map<String, Any>> {
        if (_isRecording) {
            _isRecording = false
            // Resume voice commands after recording
            voiceCommandService?.resume()
            Log.d("ActionRecorder", "Recording stopped. Actions recorded: ${recordedActions.size}")
            return recordedActions.toList()
        }
        return emptyList()
    }

    fun recordEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recordClickEvent(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> recordTextChangeEvent(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> recordWindowStateChange(event)
        }
    }

    private fun recordClickEvent(event: AccessibilityEvent) {
        val node = event.source
        if (node != null) {
            try {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)

                // Log the complete event information
                Log.d("ActionRecorder", "Recording click event: " +
                        "package=${event.packageName}, " +
                        "class=${event.className}, " +
                        "text=${node.text}, " +
                        "desc=${node.contentDescription}, " +
                        "id=${node.viewIdResourceName}, " +
                        "clickable=${node.isClickable}, " +
                        "location=(${bounds.centerX()}, ${bounds.centerY()})")

                val nodeInfo = mutableMapOf(
                    "type" to "click",
                    "x" to bounds.centerX(),
                    "y" to bounds.centerY(),
                    "packageName" to (event.packageName?.toString() ?: ""),
                    "className" to (event.className?.toString() ?: ""),
                    "text" to (node.text?.toString() ?: ""),
                    "contentDescription" to (node.contentDescription?.toString() ?: ""),
                    "viewIdResourceName" to (node.viewIdResourceName ?: ""),
                    "timestamp" to System.currentTimeMillis()
                )

                // Check for SIM selection and add extra information
                if (isSimSelectionClick(node) || isInSimSelectionDialog(event, node)) {
                    Log.d("ActionRecorder", "Recording SIM selection click")
                    nodeInfo["isSimSelection"] = true
                    nodeInfo["simButtonId"] = node.viewIdResourceName ?: ""
                    nodeInfo["simButtonText"] = node.text?.toString() ?: ""
                    nodeInfo["simButtonDesc"] = node.contentDescription?.toString() ?: ""
                    nodeInfo["simDialogClass"] = node.className?.toString() ?: ""

                    // Try to get parent information for context
                    node.parent?.let { parent ->
                        nodeInfo["parentId"] = parent.viewIdResourceName ?: ""
                        nodeInfo["parentText"] = parent.text?.toString() ?: ""
                        parent.recycle()
                    }

                    // Record window information
                    nodeInfo["windowId"] = event.windowId
                    nodeInfo["windowType"] = "sim_selection"
                }

                recordedActions.add(nodeInfo)
                Log.d("ActionRecorder", "Action recorded: $nodeInfo")
            } finally {
                node.recycle()
            }
        }
    }

    private fun isSimSelectionClick(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""

        // Log all potential SIM-related information
        Log.d("ActionRecorder", "Checking node for SIM selection: " +
                "id=$id, text=$text, desc=$desc, class=$className")

        // Samsung-specific SIM selection IDs
        val samsungSimIds = listOf(
            "select_dialog_listview",
            "chooser_list_view",
            "sim_picker_container",
            "sim1_button",
            "sim2_button",
            "sim_1",
            "sim_2",
            "sim_icon",
            "select_dialog_listview",
            "android:id/text1"
        )

        // Common SIM-related text patterns
        val simPatterns = listOf(
            "sim 1", "sim 2", "sim1", "sim2",
            "sim card 1", "sim card 2",
            "card 1", "card 2",
            "choose sim",
            "select sim",
            "use sim",
            "call with",
            "phone 1", "phone 2"
        )

        val isSamsung = id.contains("samsung", ignoreCase = true)

        val isSimRelated = when {
            // Check by ID
            samsungSimIds.any { id.contains(it, ignoreCase = true) } -> {
                Log.d("ActionRecorder", "Found SIM selection by ID: $id")
                true
            }
            // Check by text content
            simPatterns.any { pattern ->
                (text.contains(pattern, ignoreCase = true) ||
                        desc.contains(pattern, ignoreCase = true))
            } -> {
                Log.d("ActionRecorder", "Found SIM selection by text/desc: text=$text, desc=$desc")
                true
            }
            // Check Samsung-specific dialogs
            isSamsung && (className.contains("Dialog") || className.contains("AlertDialog")) -> {
                Log.d("ActionRecorder", "Found potential Samsung SIM dialog: $className")
                true
            }
            // Check dialog list items
            className.contains("ListView") && (text.contains("sim", ignoreCase = true) ||
                    desc.contains("sim", ignoreCase = true)) -> {
                Log.d("ActionRecorder", "Found SIM selection in list view")
                true
            }
            else -> false
        }

        if (isSimRelated) {
            Log.d("ActionRecorder", "Identified as SIM selection click: " +
                    "text='$text', desc='$desc', id='$id', class='$className'")
        }

        return isSimRelated
    }

    private fun isInSimSelectionDialog(event: AccessibilityEvent, node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        // Check if we're in a dialog/window that's related to SIM selection
        val windowTitle = event.text.joinToString(" ").lowercase()
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        val isSimDialog = windowTitle.contains("sim") ||
                windowTitle.contains("card") ||
                windowTitle.contains("choose") ||
                (packageName.contains("dialer") && className.contains("Dialog"))

        if (isSimDialog) {
            Log.d("ActionRecorder", "Detected SIM selection dialog: title=$windowTitle, package=$packageName, class=$className")
        }

        return isSimDialog
    }

    private fun recordTextChangeEvent(event: AccessibilityEvent) {
        val text = event.text.joinToString()
        recordedActions.add(mapOf(
            "type" to "text_change",
            "text" to text,
            "packageName" to (event.packageName?.toString() ?: ""),
            "className" to (event.className?.toString() ?: ""),
            "timestamp" to System.currentTimeMillis()
        ))
        Log.d("ActionRecorder", "Text change event recorded: $text in ${event.packageName}")
    }

    private fun recordWindowStateChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Only record app switches, not every window state change
        if (event.windowId != -1) {
            recordedActions.add(mapOf(
                "type" to "app_switch",
                "packageName" to packageName,
                "className" to className,
                "timestamp" to System.currentTimeMillis(),
                "windowId" to event.windowId
            ))
            Log.d("ActionRecorder", "Window state change recorded: $packageName")
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }
}
