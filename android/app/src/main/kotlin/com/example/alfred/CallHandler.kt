package com.example.alfred

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.N)
class CallHandler(
    service: AccessibilityService,
    scope: CoroutineScope
) : ActionPlayerBase(service, scope) {

    private val callButtonIds = listOf(
        "com.samsung.android.dialer:id/callButton",
        "com.samsung.android.dialer:id/dialButton",
        "com.samsung.android.dialer:id/voice_call_button",
        "com.samsung.android.dialer:id/fab",
        "com.samsung.android.dialer:id/call_button",
        "com.samsung.android.dialer:id/floating_action_button"
    )

    suspend fun handleCallAction(
        action: Map<String, Any>,
        currentActionIndex: Int,
        currentActions: List<Map<String, Any>>
    ) {
        try {
            val root = service.rootInActiveWindow ?: return
            val callButton = findCallButton(root)

            // Find the last non-empty text entry
            var lastTextEntry: String? = null
            for (i in currentActionIndex downTo 0) {
                val prevAction = currentActions[i]
                if (prevAction["type"] == "text_change") {
                    lastTextEntry = prevAction["text"] as? String
                    break
                }
            }

            when (callButton) {
                null -> {
                    Log.e("CallHandler", "Could not find call button")
                    val x = (action["x"] as? Number)?.toFloat() ?: return
                    val y = (action["y"] as? Number)?.toFloat() ?: return
                    performCoordinateClick(x, y)
                }
                else -> {
                    var success = false

                    withContext(Dispatchers.Main) {
                        delay(500)
                        success = callButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }

                    if (success) {
                        Log.d("CallHandler", "Call button clicked successfully")
                        delay(1500)

                        // Look ahead for SIM selection
                        var nextActionIndex = currentActionIndex + 1
                        while (nextActionIndex < currentActions.size) {
                            val nextAction = currentActions[nextActionIndex]
                            if (nextAction["isSimSelection"] == true) {
                                Log.d("CallHandler", "Found recorded SIM selection, will handle it")
                                handleSimSelection(nextAction)
                                break
                            }
                            if (nextAction["type"] != "app_switch") break
                            nextActionIndex++
                        }
                    } else {
                        success = tryAlternativeDialMethods(callButton)
                        if (!success) {
                            Log.d("CallHandler", "Failed to click call button, trying coordinate click")
                            val bounds = android.graphics.Rect()
                            callButton.getBoundsInScreen(bounds)
                            performCoordinateClick(bounds.exactCenterX(), bounds.exactCenterY())
                        }
                    }

                    callButton.recycle()
                }
            }

            root.recycle()
        } catch (e: Exception) {
            Log.e("CallHandler", "Error handling Samsung dialer: ${e.message}")
        }
    }

    suspend fun handleSimSelection(simAction: Map<String, Any>) {
        withContext(Dispatchers.Main) {
            try {
                Log.d("CallHandler", "Starting SIM selection handling with action: $simAction")
                delay(500)

                val root = service.rootInActiveWindow ?: return@withContext
                val simButton = findExactSimButton(root, simAction)

                when (simButton) {
                    null -> {
                        // Try coordinate click as fallback
                        val x = simAction["x"] as? Number
                        val y = simAction["y"] as? Number
                        if (x != null && y != null) {
                            Log.d("CallHandler", "Using coordinate click for SIM selection at $x, $y")
                            performCoordinateClick(x.toFloat(), y.toFloat())
                        } else {
                            Log.e("CallHandler", "No coordinates available for SIM selection fallback")
                        }
                    }
                    else -> {
                        val success = simButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d("CallHandler", "Clicked SIM button: ${simAction["simButtonText"]}, success=$success")
                        simButton.recycle()
                    }
                }

                root.recycle()
                delay(500)
            } catch (e: Exception) {
                Log.e("CallHandler", "Error handling SIM selection: ${e.message}")
            }
        }
    }

    private fun findCallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in callButtonIds) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    for (node in nodes) {
                        if (node.isClickable) {
                            Log.d("CallHandler", "Found clickable call button by ID: $id")
                            return node
                        }
                        node.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e("CallHandler", "Error finding button by ID: $id")
            }
        }

        // Try finding by description or text
        return AccessibilityNodeHelper.findNodeRecursive(root) { node ->
            node.isClickable && (
                    node.text?.toString()?.contains("call", true) == true ||
                            node.contentDescription?.toString()?.contains("call", true) == true ||
                            node.text?.toString()?.contains("dial", true) == true ||
                            node.contentDescription?.toString()?.contains("dial", true) == true
                    )
        }?.also {
            Log.d("CallHandler", "Found call button by text/description")
        }
    }

    private fun findExactSimButton(root: AccessibilityNodeInfo, simAction: Map<String, Any>): AccessibilityNodeInfo? {
        val viewId = simAction["simButtonId"] as? String
        val text = simAction["simButtonText"] as? String
        val desc = simAction["simButtonDesc"] as? String

        Log.d("CallHandler", "Searching for SIM button with id=$viewId, text=$text, desc=$desc")

        // Try to find by ID first
        if (!viewId.isNullOrEmpty()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (nodes.isNotEmpty()) {
                Log.d("CallHandler", "Found SIM button by ID: $viewId")
                return nodes[0]
            }
        }

        // Try to find by exact text match
        return AccessibilityNodeHelper.findNodeRecursive(root) { node ->
            node.isClickable && (
                    node.text?.toString() == text ||
                            node.contentDescription?.toString() == desc ||
                            node.viewIdResourceName == viewId
                    )
        }?.also {
            Log.d("CallHandler", "Found SIM button by text/description")
        }
    }

    private suspend fun tryAlternativeDialMethods(node: AccessibilityNodeInfo): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                delay(200)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@withContext true
                }

                delay(200)
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                delay(200)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@withContext true
                }

                delay(200)
                if (node.performAction(AccessibilityNodeInfo.ACTION_SELECT)) {
                    delay(200)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return@withContext true
                    }
                }

                delay(200)
                return@withContext node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } catch (e: Exception) {
                Log.e("CallHandler", "Error in tryAlternativeDialMethods: ${e.message}")
                false
            }
        }
    }
}