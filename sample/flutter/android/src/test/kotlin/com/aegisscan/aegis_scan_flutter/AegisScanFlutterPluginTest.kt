package com.aegisscan.aegis_scan_flutter

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlin.test.Test
import org.mockito.Mockito

/*
 * Run these from the command line with `./gradlew testDebugUnitTest` in
 * `example/android/`, or from an IDE that supports JUnit.
 */
internal class AegisScanFlutterPluginTest {
  @Test
  fun onMethodCall_unknownMethod_isNotImplemented() {
    val plugin = AegisScanFlutterPlugin()

    val call = MethodCall("unknownMethod", null)
    val mockResult: MethodChannel.Result = Mockito.mock(MethodChannel.Result::class.java)
    plugin.onMethodCall(call, mockResult)

    Mockito.verify(mockResult).notImplemented()
  }

  @Test
  fun onMethodCall_startWithNoAttachedActivity_doesNotThrow() {
    // No onAttachedToActivity() call -- matches a plugin registered but
    // not yet bound to an Activity. start() should no-op, not crash.
    val plugin = AegisScanFlutterPlugin()

    val call = MethodCall("start", null)
    val mockResult: MethodChannel.Result = Mockito.mock(MethodChannel.Result::class.java)
    plugin.onMethodCall(call, mockResult)

    Mockito.verify(mockResult).success(null)
  }
}
