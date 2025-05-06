package com.example.alfred

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.N)
abstract class ActionPlayerBase(
    protected val service: AccessibilityService,
    protected val scope: CoroutineScope
) {
    protected var speedMultiplier: Float = 1.0f
    protected var isPaused: Boolean = false

    protected suspend fun performCoordinateClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, (100 / speedMultiplier).toLong()))
            .build()

        val result = suspendCancellableCoroutine { continuation ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(true) {}
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resume(false) {}
                }
            }, null)
        }

        Log.d("ActionPlayer", "Coordinate click ${if (result) "succeeded" else "failed"} at: $x, $y")
    }

    protected suspend fun performBasicClick(action: Map<String, Any>) {
        val x = (action["x"] as? Number)?.toFloat() ?: return
        val y = (action["y"] as? Number)?.toFloat() ?: return
        val packageName = action["packageName"] as? String
        val className = action["className"] as? String
        val text = action["text"] as? String
        val contentDescription = action["contentDescription"] as? String
        val viewIdResourceName = action["viewIdResourceName"] as? String

        Log.d("ActionPlayer", "Performing basic click: packageName=$packageName, text=$text, id=$viewIdResourceName")

        AccessibilityNodeHelper.waitForApp(service, packageName)

        val targetNode = AccessibilityNodeHelper.findTargetNode(
            service,
            packageName,
            className,
            text,
            contentDescription,
            viewIdResourceName
        )

        when {
            targetNode != null -> {
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.d("ActionPlayer", "Clicked specific node: $text in $packageName")
                } else {
                    Log.e("ActionPlayer", "Failed to click specific node, trying fallback")
                    val bounds = Rect()
                    targetNode.getBoundsInScreen(bounds)
                    performCoordinateClick(bounds.exactCenterX(), bounds.exactCenterY())
                }
                targetNode.recycle()
            }
            else -> {
                Log.d("ActionPlayer", "No specific node found, using coordinate click at: $x, $y")
                performCoordinateClick(x, y)
            }
        }
    }

    protected suspend fun performAppSwitch(packageName: String?) {
        if (!packageName.isNullOrEmpty()) {
            val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                Log.d("ActionPlayer", "Switched to app: $packageName")
                delay((2000 / speedMultiplier).toLong())
            } else {
                Log.e("ActionPlayer", "Failed to get launch intent for app: $packageName")
            }
        }
    }

    protected suspend fun findAndClickNodeById(ids: List<String>, actionDescription: String): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                val root = service.rootInActiveWindow ?: return@withContext false

                for (id in ids) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        if (node.isClickable) {
                            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (clicked) {
                                Log.d("ActionPlayer", "Clicked $actionDescription by ID: $id")
                                node.recycle()
                                return@withContext true
                            }
                        }
                        node.recycle()
                    }
                }
                root.recycle()
                false
            } catch (e: Exception) {
                Log.e("ActionPlayer", "Error finding $actionDescription: ${e.message}")
                false
            }
        }
    }

    protected fun findNodeByText(root: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        return AccessibilityNodeHelper.findNodeRecursive(root) { node ->
            node.isClickable && (
                    keywords.any { keyword ->
                        node.text?.toString()?.toLowerCase()?.contains(keyword) == true ||
                                node.contentDescription?.toString()?.toLowerCase()?.contains(keyword) == true
                    }
                    )
        }
    }

    open fun setPlaybackSpeed(speed: Float) {
        speedMultiplier = speed
    }

    protected fun recycleNodes(vararg nodes: AccessibilityNodeInfo?) {
        nodes.forEach { node ->
            try {
                node?.recycle()
            } catch (e: Exception) {
                Log.e("ActionPlayer", "Error recycling node: ${e.message}")
            }
        }
    }

    open fun pause() {
        isPaused = true
    }

    open fun resume() {
        isPaused = false
    }
}
