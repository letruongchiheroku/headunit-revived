package com.andrerinas.headunitrevived.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.UsbAccessoryMode
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.main.MainActivity
import com.andrerinas.headunitrevived.utils.Settings


class UsbAttachedActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppLog.i("USB Intent: $intent")

        val device = DeviceIntent(intent).device
        if (device == null) {
            AppLog.e("No USB device")
            finish()
            return
        }

        val settings = Settings(this)
        if (settings.autoStartOnUsb && !AapService.isConnected && !App.provide(this).transport.isAlive) {
            AppLog.i("USB auto-start: launching app")
            try {
                val launchIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(launchIntent)
            } catch (e: Exception) {
                AppLog.w("Could not start UI from USB auto-start: ${e.message}")
            }
        }

        if (App.provide(this).transport.isAlive) {
            AppLog.e("Thread already running")
            finish()
            return
        }

        if (UsbDeviceCompat.isInAccessoryMode(device)) {
            AppLog.e("Usb in accessory mode")
            ContextCompat.startForegroundService(this, AapService.createIntent(device, this))
            finish()
            return
        }

        val deviceCompat = UsbDeviceCompat(device)
        if (!settings.isConnectingDevice(deviceCompat)) {
            AppLog.i("Skipping device " + deviceCompat.uniqueName)
            finish()
            return
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val usbMode = UsbAccessoryMode(usbManager)
        AppLog.i("Switching USB device to accessory mode " + deviceCompat.uniqueName)
        Toast.makeText(this, getString(R.string.switching_usb_accessory_mode, deviceCompat.uniqueName), Toast.LENGTH_SHORT).show()
        if (usbMode.connectAndSwitch(device)) {
            Toast.makeText(this, getString(R.string.success), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.failed), Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val device = DeviceIntent(getIntent()).device
        if (device == null) {
            AppLog.e("No USB device")
            finish()
            return
        }

        AppLog.i(UsbDeviceCompat.getUniqueName(device))

        if (!App.provide(this).transport.isAlive) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                AppLog.e("Usb in accessory mode")
                ContextCompat.startForegroundService(this, AapService.createIntent(device, this))
            }
        } else {
            AppLog.e("Thread already running")
        }

        finish()
    }
}
