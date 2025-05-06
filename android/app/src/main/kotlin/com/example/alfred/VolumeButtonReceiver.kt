package com.example.alfred

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class VolumeButtonReceiver : BroadcastReceiver() {
    companion object {
        private const val DOUBLE_CLICK_TIMEOUT = 500 // milliseconds
        private var lastClickTime: Long = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastClickTime < DOUBLE_CLICK_TIMEOUT) {
                // Double click detected
                val inputService = InputService.getInstance()
                if (inputService != null) {
                    if (inputService.isRecording) {
                        inputService.stopRecording()
                    } else {
                        inputService.startRecording()
                    }
                }
            }
            lastClickTime = currentTime
        }
    }
}