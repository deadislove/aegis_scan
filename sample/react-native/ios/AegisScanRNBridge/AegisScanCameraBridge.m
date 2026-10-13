// Copyright 2026 AegisScan Authors. All rights reserved.
//
// Classic-bridge Objective-C stub: declares AegisScanCamera.swift's
// exported methods to the RN bridge. The implementation (including event
// emission) lives entirely in Swift; this file exists only because the
// bridge macros are Objective-C-only.
#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(AegisScanCamera, NSObject)

RCT_EXTERN_METHOD(start)
RCT_EXTERN_METHOD(stop)

@end
