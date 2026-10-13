// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscanrnsample.bridge

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.aegisscan.android.AndroidCameraScannerEngine
import com.aegisscan.android.ScanEvent
import com.aegisscan.android.ScannerConfig
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap

/**
 * Native camera preview for the RN bridge: a [FrameLayout] wrapping a
 * [PreviewView] (final in CameraX -- can't be subclassed, same reason
 * sample/android's MainActivity composes one via XML instead of
 * subclassing it) that owns an [AndroidCameraScannerEngine] bound to the
 * hosting Activity's lifecycle (RN's `ReactActivity` is a
 * `LifecycleOwner`). [AegisScanCameraModule] starts/stops whichever
 * instance is currently mounted.
 */
class AegisScanCameraView(context: Context) : FrameLayout(context) {
    private val previewView = PreviewView(context).also {
        it.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    private var engine: AndroidCameraScannerEngine? = null

    init {
        addView(previewView)
        AegisScanCameraModule.currentView = this
    }

    fun startScanning(emit: (String, WritableMap) -> Unit) {
        if (engine != null) return
        val lifecycleOwner = context as? LifecycleOwner ?: return
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val scannerEngine = AndroidCameraScannerEngine(
            context = context,
            lifecycleOwner = lifecycleOwner,
            config = ScannerConfig(),
            listener = { event -> handleEvent(event, emit) },
        )
        engine = scannerEngine
        scannerEngine.start(preview)
    }

    fun stopScanning() {
        engine?.stop()
        engine = null
    }

    /** PAN/expiry/cardholderName are the only card fields crossing the
     * bridge -- never raw digits, same redacted shape as every other
     * platform's camera pipeline integration in this SDK. */
    private fun handleEvent(event: ScanEvent, emit: (String, WritableMap) -> Unit) {
        when (event) {
            is ScanEvent.CardDetected -> {
                val pan = event.pan
                val body = Arguments.createMap().apply {
                    putString("brand", pan.brand.toString())
                    putString("last4", pan.last4)
                    pan.expiry?.let {
                        putMap(
                            "expiry",
                            Arguments.createMap().apply {
                                putInt("month", it.month)
                                putInt("year", it.year)
                            },
                        )
                    }
                    pan.cardholderName?.let { putString("cardholderName", it) }
                }
                emit("onCardDetected", body)
            }
            is ScanEvent.VouchersMatched -> {
                val first = event.matches.first()
                val body = Arguments.createMap().apply {
                    putString("payload", first.barcode.payload)
                    putString("associatedText", first.associatedText)
                }
                emit("onVouchersMatched", body)
            }
            is ScanEvent.Error -> {
                val body = Arguments.createMap().apply {
                    putString("message", event.error.message ?: "")
                }
                emit("onError", body)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScanning()
        if (AegisScanCameraModule.currentView === this) AegisScanCameraModule.currentView = null
    }
}
