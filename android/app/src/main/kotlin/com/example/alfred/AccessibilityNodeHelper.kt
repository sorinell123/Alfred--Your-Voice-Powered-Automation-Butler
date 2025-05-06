package com.example.alfred

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

object AccessibilityNodeHelper {
    suspend fun waitForApp(service: AccessibilityService, packageName: String?) {
        if (packageName == null) {
            return
        }
        withTimeoutOrNull(5000) {
            while (service.rootInActiveWindow?.packageName?.toString() != packageName) {
                delay(100)
            }
        }
    }

    fun findTargetNode(service: AccessibilityService, packageName: String?, className: String?, text: String?, contentDescription: String?, viewIdResourceName: String?): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null

        return root.findAccessibilityNodeInfosByViewId(viewIdResourceName ?: "").firstOrNull()
            ?: findNodeRecursive(root) { node ->
                node.packageName == packageName &&
                        node.className == className &&
                        (node.text?.toString() == text || node.contentDescription?.toString() == contentDescription)
            }
    }

    fun findNodeRecursive(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, predicate)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val editableChild = findEditableNode(child)
            if (editableChild != null) return editableChild
            child.recycle()
        }
        return null
    }
}