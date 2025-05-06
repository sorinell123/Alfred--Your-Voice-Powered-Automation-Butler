package com.example.alfred

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.view.MotionEvent

class FloatingButtonService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: View
    private lateinit var recordButton: ImageButton
    private var isRecording = false
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.alfred.RECORDING_STATE_CHANGED") {
                isRecording = intent.getBooleanExtra("isRecording", false)
                updateButtonState()
            }
        }
    }

    private val PREFS_NAME = "FloatingButtonPrefs"
    private val PREF_X = "x"
    private val PREF_Y = "y"

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("FloatingButtonService", "Service onCreate called")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingButton = inflater.inflate(R.layout.floating_button, null)
        recordButton = floatingButton.findViewById(R.id.floatingRecordButton)
        // Set initial state
        recordButton.setBackground(getDrawable(R.drawable.circular_button_green))
        recordButton.setImageResource(R.drawable.ic_record_play)

        // Get the last saved position
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getInt(PREF_X, 0)
        val savedY = prefs.getInt(PREF_Y, 100)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = savedX
        params.y = savedY

        // Add touch listener for drag functionality
        floatingButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingButton, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(floatingButton, params)
            Log.d("FloatingButtonService", "Floating button added to window")
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error adding view: ${e.message}")
        }

        recordButton.setOnClickListener(null) // Remove existing click listener
        recordButton.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    // If it's a small movement, treat it as a potential click
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        return@setOnTouchListener true
                    }
                    
                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    windowManager.updateViewLayout(floatingButton, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = Math.abs(event.rawX - initialTouchX)
                    val deltaY = Math.abs(event.rawY - initialTouchY)
                    
                    // If it was a small movement, treat it as a click
                    if (deltaX < 10 && deltaY < 10) {
                        Log.d("FloatingButtonService", "Floating button clicked")
                        val intent = Intent("com.example.alfred.TRIGGER_RECORDING_BUTTON")
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                        Log.d("FloatingButtonService", "Sent TRIGGER_RECORDING_BUTTON broadcast")
                    }

                    // Save the new position
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_X, params.x)
                        .putInt(PREF_Y, params.y)
                        .apply()
                    
                    true
                }
                else -> false
            }
        }

        // Register for recording state changes
        val filter = IntentFilter("com.example.alfred.RECORDING_STATE_CHANGED")
        registerReceiver(recordingStateReceiver, filter)
    }

    private fun updateButtonState() {
        try {
            // Update button appearance based on recording state
            val drawableRes = if (isRecording) {
                R.drawable.ic_record_stop
            } else {
                R.drawable.ic_record_play
            }
            
            val backgroundRes = if (isRecording) {
                R.drawable.circular_button_red
            } else {
                R.drawable.circular_button_green
            }
            
            recordButton.setImageResource(drawableRes)
            recordButton.setBackground(getDrawable(backgroundRes))
            Log.d("FloatingButtonService", "Updated button state: recording = $isRecording")
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error updating button state: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FloatingButtonService", "Service onStartCommand called")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FloatingButtonService", "Service onDestroy called")
        try {
            unregisterReceiver(recordingStateReceiver)
        } catch (e: Exception) {
            Log.e("FloatingButtonService", "Error unregistering receiver: ${e.message}")
        }
        if (::windowManager.isInitialized && ::floatingButton.isInitialized) {
            windowManager.removeView(floatingButton)
        }
    }
}
