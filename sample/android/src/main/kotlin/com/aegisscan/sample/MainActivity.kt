// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.aegisscan.android.AndroidCameraScannerEngine
import com.aegisscan.android.ScanEvent
import com.aegisscan.android.ScannerConfig

/**
 * Minimal integration of [AndroidCameraScannerEngine]: a CameraX [Preview]
 * bound alongside the scanner's analysis use case on this activity's
 * lifecycle. See README.md for the PCI-DSS rule that applies once you wire a
 * real card result to your own backend.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private var engine: AndroidCameraScannerEngine? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startScanning() else statusText.text = "Camera permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startScanning()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val scannerEngine = AndroidCameraScannerEngine(
            context = this,
            lifecycleOwner = this,
            config = ScannerConfig(),
            listener = ::onScanEvent,
        )
        engine = scannerEngine
        scannerEngine.start(preview)
        statusText.text = "Scanning…"
    }

    /** Called on the main thread; `event.pan` (if any) is wiped right after this returns. */
    private fun onScanEvent(event: ScanEvent) {
        when (event) {
            is ScanEvent.CardDetected -> {
                // This is where a real integration hands the digits to its
                // own tokenizer/HSM call. The sample only displays the brand
                // and last 4 digits, which are safe to show under PCI-DSS 3.4.
                val pan = event.pan
                statusText.text = "Card: ${pan.brand} ****${pan.last4}"
            }
            is ScanEvent.VouchersMatched -> {
                val first = event.matches.first()
                statusText.text = "Voucher: ${first.barcode.payload} -> ${first.associatedText}"
            }
            is ScanEvent.Error -> statusText.text = "Error: ${event.error.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine = null  // AndroidCameraScannerEngine.shutdown() already runs via ON_DESTROY.
    }
}
