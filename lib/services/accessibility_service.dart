import 'package:flutter/services.dart';

class AccessibilityService {
  static const platform = MethodChannel('com.example.alfred/accessibility');

  Future<bool> isServiceEnabled() async {
    try {
      final bool result = await platform.invokeMethod('isAccessibilityServiceEnabled');
      print("Accessibility Service Status: $result");
      return result;
    } on PlatformException catch (e) {
      print("Failed to check accessibility service: '${e.message}'.");
      return false;
    }
  }

  Future<void> openAccessibilitySettings() async {
    try {
      await platform.invokeMethod('openAccessibilitySettings');
    } on PlatformException catch (e) {
      print("Failed to open accessibility settings: '${e.message}'.");
    }
  }
}