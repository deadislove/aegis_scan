// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.aegis_scan_flutter

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.aegisscan.android.AndroidCameraScannerEngine
import com.aegisscan.android.ScanEvent
import com.aegisscan.android.ScannerConfig
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Camera preview PlatformView: a [FrameLayout] wrapping a [PreviewView]
 * (final in CameraX -- can't be subclassed) that owns an
 * [AndroidCameraScannerEngine] bound to the hosting Activity's lifecycle.
 * Same shape as the React Native bridge's
 * ../../../react-native/android/.../AegisScanCameraView.kt.
 */
class AegisScanCameraPlatformViewFactory(
    private val messenger: io.flutter.plugin.common.BinaryMessenger,
) : PlatformViewFactory(io.flutter.plugin.common.StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView =
        AegisScanCameraPlatformView(context)
}

class AegisScanCameraPlatformView(context: Context) : PlatformView {
    private val container = FrameLayout(context)
    private val previewView = PreviewView(context).also {
        it.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    private var engine: AndroidCameraScannerEngine? = null

    init {
        container.addView(previewView)
        AegisScanFlutterPlugin.currentView = this
    }

    override fun getView(): View = container

    fun startScanning(emit: (String, Map<String, Any?>) -> Unit) {
        if (engine != null) return
        val lifecycleOwner = container.context as? LifecycleOwner ?: return
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val scannerEngine = AndroidCameraScannerEngine(
            context = container.context,
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
     * channel -- never raw digits, same redacted shape as every other
     * platform's camera pipeline integration in this SDK. */
    private fun handleEvent(event: ScanEvent, emit: (String, Map<String, Any?>) -> Unit) {
        when (event) {
            is ScanEvent.CardDetected -> {
                val pan = event.pan
                val card = mutableMapOf<String, Any?>(
                    "brand" to pan.brand.toString(),
                    "last4" to pan.last4,
                )
                pan.expiry?.let { card["expiry"] = mapOf("month" to it.month, "year" to it.year) }
                pan.cardholderName?.let { card["cardholderName"] = it }
                emit("card", mapOf("type" to "card", "card" to card))
            }
            is ScanEvent.VouchersMatched -> {
                val first = event.matches.first()
                val match = mapOf(
                    "payload" to first.barcode.payload,
                    "associatedText" to first.associatedText,
                )
                emit("vouchers", mapOf("type" to "vouchers", "match" to match))
            }
            is ScanEvent.Error -> {
                emit("error", mapOf("type" to "error", "message" to (event.error.message ?: "")))
            }
        }
    }

    override fun dispose() {
        stopScanning()
        if (AegisScanFlutterPlugin.currentView === this) AegisScanFlutterPlugin.currentView = null
    }
}
