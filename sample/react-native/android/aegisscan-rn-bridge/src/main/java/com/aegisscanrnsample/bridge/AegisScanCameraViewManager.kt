// Copyright 2026 AegisScan Authors. All rights reserved.
package com.aegisscanrnsample.bridge

import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext

/** Exposes [AegisScanCameraView] to JSX as `<AegisScanCameraView />`. */
class AegisScanCameraViewManager : SimpleViewManager<AegisScanCameraView>() {

    override fun getName() = "AegisScanCameraView"

    override fun createViewInstance(context: ThemedReactContext): AegisScanCameraView =
        AegisScanCameraView(context)
}
