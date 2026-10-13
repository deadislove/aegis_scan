/**
 * AegisScan React Native sample.
 *
 * Mirrors every other platform sample's shape (sample/ios/CameraApp,
 * sample/android, sample/web/main.js): full-screen camera preview, a
 * status overlay showing brand/last4/expiry/cardholderName on a detected
 * card. See src/AegisScanCamera.tsx for the native bridge this wraps.
 *
 * @format
 */

import { useEffect, useState } from 'react';
import {
  PermissionsAndroid, Platform, StatusBar, StyleSheet, Text, View,
} from 'react-native';
import {
  AegisScanCameraView, onCardDetected, onScanError, onVouchersMatched,
  startScanning, stopScanning,
} from './src/AegisScanCamera';

function App() {
  const [statusText, setStatusText] = useState('Point the camera at a card or voucher');

  useEffect(() => {
    const cardSub = onCardDetected((card) => {
      let text = `${card.brand} ****${card.last4}`;
      if (card.expiry) text += ` exp ${card.expiry.month}/${card.expiry.year}`;
      if (card.cardholderName) text += ` (${card.cardholderName})`;
      setStatusText(text);
    });
    const voucherSub = onVouchersMatched((match) => {
      setStatusText(`Voucher: ${match.payload} -> ${match.associatedText}`);
    });
    const errorSub = onScanError((error) => {
      setStatusText(`Error: ${error.message}`);
    });

    requestCameraPermission().then((granted) => {
      if (granted) startScanning();
      else setStatusText('Camera permission denied');
    });

    return () => {
      cardSub.remove();
      voucherSub.remove();
      errorSub.remove();
      stopScanning();
    };
  }, []);

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />
      <AegisScanCameraView style={styles.camera} />
      <View style={styles.overlay}>
        <Text style={styles.statusText}>{statusText}</Text>
      </View>
    </View>
  );
}

async function requestCameraPermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true; // iOS: OS prompts on first use.
  const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA);
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: 'black' },
  camera: { flex: 1 },
  overlay: {
    position: 'absolute', bottom: 0, left: 0, right: 0, padding: 16,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  statusText: { color: 'white', fontSize: 16, textAlign: 'center' },
});

export default App;
