// Copyright 2026 AegisScan Authors. All rights reserved.
export { FintechWebScanner, ScannerError } from './FintechWebScanner.js';
export type { CardScan, FintechWebScannerOptions, ScannerErrorCode, ScannerStats } from './FintechWebScanner.js';
export {
  validate, detectBrand, extractCard, extractCardDetails, extractExpiry, extractCardholderName,
  repairAndExtract, CardBrand,
} from './core/luhn.js';
export type { ExtractedPan, ExpiryDate } from './core/luhn.js';
export { extractMrz } from './core/mrz.js';
export type { MrzResult } from './core/mrz.js';
export { SpatialMatcher } from './core/spatialMatcher.js';
export type { BarcodeItem, TextItem, MatchResult, BoundingBox, Alignment } from './core/spatialMatcher.js';
export type { WireVoucher, OcrLine } from './protocol.js';
