import 'package:flutter/services.dart';

class FloatingButtonService {
  static const platform = MethodChannel('com.example.alfred/floating_button');

  Future<void> showFloatingButton() async {
    try {
      await platform.invokeMethod('showFloatingButton');
    } on PlatformException catch (e) {
      print("Failed to show floating button: '${e.message}'.");
    }
  }

  Future<void> hideFloatingButton() async {
    try {
      await platform.invokeMethod('hideFloatingButton');
    } on PlatformException catch (e) {
      print("Failed to hide floating button: '${e.message}'.");
    }
  }
}