package com.andrerinas.headunitrevived.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SystemSettings
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import java.util.Calendar

class NightModeManager(
    private val context: Context,
    private val settings: Settings,
    private val onUpdate: (Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var nightModeCalculator = NightMode(settings, false)
    
    // State
    private var lastEmittedValue: Boolean? = null
    private var currentLux: Float = -1f
    private var currentBrightness: Int = -1
    private var isFirstSensorReading = true
    private var isSensorRegistered = false
    private var isObserverRegistered = false
    
    private val handler = Handler(Looper.getMainLooper())

    // Debouncing
    private var pendingValue: Boolean? = null
    private val debounceRunnable = Runnable {
        pendingValue?.let { newValue ->
            if (lastEmittedValue != newValue) {
                lastEmittedValue = newValue
                onUpdate(newValue)
            }
        }
    }

    private val brightnessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationUpdateIntent.action) {
                // Keep the sensor values when recreating the calculator
                val oldLux = currentLux
                val oldBright = currentBrightness
                nightModeCalculator = NightMode(settings, true)
                nightModeCalculator.currentLux = oldLux
                nightModeCalculator.currentBrightness = oldBright
            }
            update()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(LocationUpdateIntent.action)
        }
        
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        // Initial setup of sensors/observers based on current settings
        refreshListeners()

        // Initial update immediately
        // Reset lastEmittedValue to ensure initial state is sent
        lastEmittedValue = null
        update(debounce = false)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isSensorRegistered) {
            sensorManager.unregisterListener(this)
            isSensorRegistered = false
        }
        if (isObserverRegistered) {
            context.contentResolver.unregisterContentObserver(brightnessObserver)
            isObserverRegistered = false
        }
        handler.removeCallbacks(debounceRunnable)
    }

    fun resendCurrentState() {
        // Force a fresh check and SEND even if value hasn't changed (e.g. new connection)
        nightModeCalculator = NightMode(settings, true)
        nightModeCalculator.currentLux = currentLux
        nightModeCalculator.currentBrightness = currentBrightness
        
        refreshListeners()
        lastEmittedValue = null // Invalidate cache to force emission
        update(debounce = false)
    }

    private fun refreshListeners() {
        // 1. Light Sensor
        if (settings.nightMode == Settings.NightMode.LIGHT_SENSOR) {
            if (!isSensorRegistered && lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorRegistered = true
            }
        } else {
            if (isSensorRegistered) {
                sensorManager.unregisterListener(this)
                isSensorRegistered = false
            }
        }

        // 2. Brightness Observer
        if (settings.nightMode == Settings.NightMode.SCREEN_BRIGHTNESS) {
            if (!isObserverRegistered) {
                context.contentResolver.registerContentObserver(
                    SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
                    false,
                    brightnessObserver
                )
                isObserverRegistered = true
            }
        } else {
            if (isObserverRegistered) {
                context.contentResolver.unregisterContentObserver(brightnessObserver)
                isObserverRegistered = false
            }
        }
    }

    // Made public so Service can force an update
    fun update(debounce: Boolean = true) {
        var isNight = false
        val threshold = settings.nightModeThresholdLux
        val thresholdBrightness = settings.nightModeThresholdBrightness

        when (settings.nightMode) {
            Settings.NightMode.LIGHT_SENSOR -> {
                if (currentLux >= 0) {
                    // Hysteresis Logic
                    val hyst = 5.0f // 5 Lux buffer
                    val currentIsNight = lastEmittedValue ?: false
                    
                    isNight = if (currentIsNight) {
                        currentLux < (threshold + hyst)
                    } else {
                        currentLux < threshold
                    }
                }
            }
            Settings.NightMode.SCREEN_BRIGHTNESS -> {
                try {
                    currentBrightness = SystemSettings.System.getInt(
                        context.contentResolver, 
                        SystemSettings.System.SCREEN_BRIGHTNESS
                    )
                    // Hysteresis Logic for Brightness
                    val hyst = 10 // 10 steps buffer
                    val currentIsNight = lastEmittedValue ?: false
                    
                    isNight = if (currentIsNight) {
                        currentBrightness < (thresholdBrightness + hyst)
                    } else {
                        currentBrightness < thresholdBrightness
                    }
                } catch (e: Exception) {
                    AppLog.e("NightModeManager: Failed to read brightness", e)
                }
            }
            // Delegate to standard calculator for other modes (Auto, Day, Night, Manual)
            else -> {
                // Ensure calculator has latest settings reference/values
                isNight = nightModeCalculator.current
            }
        }

        // Apply
        if (debounce) {
            if (pendingValue != isNight) {
                pendingValue = isNight
                handler.removeCallbacks(debounceRunnable)
                handler.postDelayed(debounceRunnable, 2000)
            }
        } else {
            // Immediate update
            handler.removeCallbacks(debounceRunnable) // Cancel any pending debounce
            if (lastEmittedValue != isNight) {
                lastEmittedValue = isNight
                onUpdate(isNight)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            
            // Only update if value changed significantly or it's the first reading
            if (kotlin.math.abs(newLux - currentLux) >= 1.0f || isFirstSensorReading) {
                currentLux = newLux
                nightModeCalculator.currentLux = currentLux
                
                if (isFirstSensorReading) {
                    isFirstSensorReading = false
                    update(debounce = false)
                } else {
                    update()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}