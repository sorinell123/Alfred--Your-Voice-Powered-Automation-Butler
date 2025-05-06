package com.example.alfred

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.N)
class MessageHandler(
    service: AccessibilityService,
    scope: CoroutineScope
) : ActionPlayerBase(service, scope) {

    private var isInComposeMode = false
    private var isInMessageMode = false
    private var lastPhoneNumber = ""
    private var lastMessage = ""
    private var shouldSend = false

    companion object {
        private const val SAMSUNG_MESSAGE_PACKAGE = "com.samsung.android.messaging"
        private val COMPOSE_BUTTON_IDS = listOf(
            "com.samsung.android.messaging:id/fab",
            "com.samsung.android.messaging:id/new_message_button",
            "com.samsung.android.messaging:id/new_conversation_button",
            "com.samsung.android.messaging:id/compose_fab",
            "com.samsung.android.messaging:id/floating_action_button"
        )

        private val RECIPIENT_FIELD_IDS = listOf(
            "com.samsung.android.messaging:id/recipient_edit_text",
            "com.samsung.android.messaging:id/recipients_editor",
            "com.samsung.android.messaging:id/recipient_text_view",
            "com.samsung.android.messaging:id/chips_recipient_edit_text",
            "com.samsung.android.messaging:id/recipients_editor_view",
            "com.samsung.android.messaging:id/phone_number_edit_text",
            "com.samsung.android.messaging:id/recipient_text_editor"
        )

        private val MESSAGE_FIELD_IDS = listOf(
            "com.samsung.android.messaging:id/message_edit_text",
            "com.samsung.android.messaging:id/embedded_text_editor",
            "com.samsung.android.messaging:id/composer_text_view",
            "android:id/message",
            "com.samsung.android.messaging:id/message_content",
            "com.samsung.android.messaging:id/message_text",
            "com.samsung.android.messaging:id/message_edit_view"
        )

        private val SEND_BUTTON_IDS = listOf(
            "com.samsung.android.messaging:id/send_button",
            "com.samsung.android.messaging:id/send_message_button",
            "com.samsung.android.messaging:id/send_message_button_icon",
            "com.samsung.android.messaging:id/msg_send"
        )
    }

    suspend fun handleTextEntry(text: String, packageName: String?, isCompose: Boolean) {
        // Skip empty text entries for dialer to prevent number deletion
        if (text.isEmpty() && packageName?.contains("dialer") == true) {
            Log.d("MessageHandler", "Skipping empty text entry for dialer")
            return
        }

        Log.d("MessageHandler", "Performing text entry: text=$text, package=$packageName, isCompose=$isCompose")

        if (isRecipientInput(text)) {
            // Update phone number tracking
            if (text != lastPhoneNumber) {
                lastPhoneNumber = text
                isInMessageMode = false
                Log.d("MessageHandler", "Updated phone number: $text")
            }
        } else {
            // We're entering message text
            if (!isInMessageMode) {
                isInMessageMode = true
                lastMessage = ""
                Log.d("MessageHandler", "Switched to message mode")
            }
            // Check if this is the complete message
            if (text.length < lastMessage.length || text == lastMessage) {
                shouldSend = true
                Log.d("MessageHandler", "Detected complete message: $text")
            }
            lastMessage = text
        }

        // Handle compose mode
        if (packageName == SAMSUNG_MESSAGE_PACKAGE && !isInComposeMode) {
            when {
                findAndClickComposeButton() -> {
                    isInComposeMode = true
                    delay(1500)
                }
                else -> {
                    Log.e("MessageHandler", "Failed to click compose button")
                    return
                }
            }
        }

        AccessibilityNodeHelper.waitForApp(service, packageName)
        performTextEntry(text, packageName, isCompose)
    }

    private suspend fun findAndClickComposeButton(): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                Log.d("MessageHandler", "Attempting to find compose button")

                // Try by ID first
                if (findAndClickNodeById(COMPOSE_BUTTON_IDS, "compose button")) {
                    return@withContext true
                }

                val root = service.rootInActiveWindow ?: return@withContext false

                try {
                    // Try by text/description
                    val keywords = listOf("compose", "new message", "new", "write", "create")
                    val composeNode = findNodeByText(root, keywords)

                    if (composeNode != null) {
                        val clicked = composeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        composeNode.recycle()
                        if (clicked) {
                            Log.d("MessageHandler", "Clicked compose button by text/description")
                            return@withContext true
                        }
                    }

                    // Try coordinate clicks
                    val screenHeight = service.resources.displayMetrics.heightPixels
                    val screenWidth = service.resources.displayMetrics.widthPixels

                    val coordinates = listOf(
                        Pair(screenWidth * 0.9f, screenHeight * 0.9f),
                        Pair(screenWidth * 0.9f, screenHeight * 0.8f),
                        Pair(screenWidth * 0.5f, screenHeight * 0.1f),
                        Pair(screenWidth * 0.1f, screenHeight * 0.1f)
                    )

                    for ((x, y) in coordinates) {
                        performCoordinateClick(x, y)
                        delay(1000)
                        if (isInComposeView()) {
                            Log.d("MessageHandler", "Successfully entered compose view after clicking at $x, $y")
                            return@withContext true
                        }
                    }

                    false
                } finally {
                    root.recycle()
                }
            } catch (e: Exception) {
                Log.e("MessageHandler", "Error finding compose button: ${e.message}")
                false
            }
        }
    }

    private suspend fun findAndClickSendButton(): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                // Try by ID first
                if (findAndClickNodeById(SEND_BUTTON_IDS, "send button")) {
                    return@withContext true
                }

                val root = service.rootInActiveWindow ?: return@withContext false

                try {
                    // Try by text/description
                    val keywords = listOf("send", "submit")
                    val sendNode = findNodeByText(root, keywords)

                    if (sendNode != null) {
                        val clicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        sendNode.recycle()
                        if (clicked) {
                            Log.d("MessageHandler", "Clicked send button by text/description")
                            return@withContext true
                        }
                    }

                    // Try by relative position
                    val screenHeight = service.resources.displayMetrics.heightPixels
                    val screenWidth = service.resources.displayMetrics.widthPixels

                    performCoordinateClick(screenWidth * 0.95f, screenHeight * 0.95f)
                    delay(500)

                    if (!isInComposeView()) {
                        Log.d("MessageHandler", "Clicked send button by coordinates")
                        return@withContext true
                    }

                    false
                } finally {
                    root.recycle()
                }
            } catch (e: Exception) {
                Log.e("MessageHandler", "Error finding send button: ${e.message}")
                false
            }
        }
    }

    private suspend fun performTextEntry(text: String, packageName: String?, isCompose: Boolean) {
        withContext(Dispatchers.Main) {
            try {
                val root = service.rootInActiveWindow ?: return@withContext

                try {
                    val fieldIds = if (isRecipientInput(text)) RECIPIENT_FIELD_IDS else MESSAGE_FIELD_IDS
                    var editableNode: AccessibilityNodeInfo? = null

                    for (id in fieldIds) {
                        val nodes = root.findAccessibilityNodeInfosByViewId(id)
                        if (nodes.isNotEmpty()) {
                            Log.d("MessageHandler", "Found input field by ID: $id")
                            editableNode = nodes[0]
                            break
                        }
                    }

                    if (editableNode == null) {
                        Log.d("MessageHandler", "No specific ID found, searching for generic editable node")
                        editableNode = AccessibilityNodeHelper.findEditableNode(root)
                    }

                    when (editableNode) {
                        null -> {
                            Log.e("MessageHandler", "No editable field found for text entry in $packageName")
                        }
                        else -> {
                            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            delay(100)

                            val bundle = androidx.core.os.bundleOf(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to text
                            )
                            val success = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

                            if (success) {
                                Log.d("MessageHandler", "Text entry performed: $text in $packageName")

                                // Only send if message is complete and we're in message mode
                                if (isInMessageMode && shouldSend) {
                                    delay(500)
                                    if (findAndClickSendButton()) {
                                        Log.d("MessageHandler", "Message sent successfully")
                                        resetMessageState()
                                    }
                                }
                            } else {
                                Log.e("MessageHandler", "Failed to perform text entry")
                            }

                            editableNode.recycle()
                        }
                    }
                } finally {
                    root.recycle()
                }
            } catch (e: Exception) {
                Log.e("MessageHandler", "Failed to perform text entry: ${e.message}")
            }
        }
    }

    private fun isRecipientInput(text: String): Boolean {
        // Improved recipient detection to handle spaces and empty text
        return text.isEmpty() || text.all { it.isDigit() || it in " ,;+" }
    }

    private fun isInComposeView(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            for (id in RECIPIENT_FIELD_IDS + MESSAGE_FIELD_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    nodes.forEach { it.recycle() }
                    return true
                }
            }
            return false
        } finally {
            root.recycle()
        }
    }

    private fun resetMessageState() {
        isInMessageMode = false
        shouldSend = false
        lastMessage = ""
        lastPhoneNumber = ""
    }

    fun reset() {
        isInComposeMode = false
        resetMessageState()
    }
}