// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscanrnsample.bridge

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * React Native (classic bridge) native module wrapping
 * [com.aegisscan.android.AndroidCameraScannerEngine] via whichever
 * [AegisScanCameraView] is currently mounted. JS calls start()/stop();
 * card/voucher/error events are forwarded as RCTDeviceEventEmitter events,
 * subscribed to from JS via NativeEventEmitter (see
 * ../../src/AegisScanCamera.ts).
 */
class AegisScanCameraModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "AegisScanCamera"

    @ReactMethod
    fun start() {
        reactContext.currentActivity?.runOnUiThread {
            currentView?.startScanning(::emit)
        }
    }

    @ReactMethod
    fun stop() {
        reactContext.currentActivity?.runOnUiThread {
            currentView?.stopScanning()
        }
    }

    private fun emit(name: String, body: WritableMap) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(name, body)
    }

    companion object {
        var currentView: AegisScanCameraView? = null
    }
}
