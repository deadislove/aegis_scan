// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscan.aegis_scan_flutter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener

private const val CAMERA_PERMISSION_REQUEST_CODE = 7246

/**
 * Flutter plugin wrapping AegisScan's AndroidCameraScannerEngine (via
 * whichever [AegisScanCameraPlatformView] is currently mounted). Dart
 * calls start()/stop() over a MethodChannel; card/voucher/error events are
 * forwarded over an EventChannel. Same shape as the React Native bridge
 * (../../../react-native/android/.../AegisScanCameraModule.kt) -- PCI-DSS
 * rationale lives there, not repeated here.
 */
class AegisScanFlutterPlugin :
    FlutterPlugin, MethodCallHandler, ActivityAware, EventChannel.StreamHandler,
    RequestPermissionsResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var activity: Activity? = null
    private var pendingStart = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "aegis_scan_flutter")
        channel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "aegis_scan_flutter/events")
        eventChannel.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "start" -> {
                requestCameraPermissionThenStart()
                result.success(null)
            }
            "stop" -> {
                currentView?.stopScanning()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun requestCameraPermissionThenStart() {
        val act = activity
        if (act == null) return
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            currentView?.startScanning(::emit)
        } else {
            pendingStart = true
            ActivityCompat.requestPermissions(
                act, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) return false
        if (pendingStart && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            currentView?.startScanning(::emit)
        } else {
            emit("error", mapOf("type" to "error", "message" to "Camera permission denied"))
        }
        pendingStart = false
        return true
    }

    private fun emit(name: String, body: Map<String, Any?>) {
        eventSink?.success(body)
    }

    private var eventSink: EventChannel.EventSink? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    companion object {
        var currentView: AegisScanCameraPlatformView? = null
    }
}
