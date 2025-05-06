import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:alfred/screens/home_screen.dart';
import 'package:alfred/services/accessibility_service.dart';
import 'package:alfred/services/macro_storage_service.dart';
import 'package:alfred/services/recorder_service.dart';
import 'package:alfred/services/playback_service.dart';
import 'package:alfred/services/floating_button_service.dart';

void main() {
  // Ensure Flutter bindings are initialized
  WidgetsFlutterBinding.ensureInitialized();

  runApp(const AlfredApp());
}

class AlfredApp extends StatelessWidget {
  const AlfredApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Alfred',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      home: HomeScreen(
        accessibilityService: AccessibilityService(),
        macroStorageService: MacroStorageService(),
        recorderService: RecorderService(),
        playbackService: PlaybackService(),
        floatingButtonService: FloatingButtonService(),
      ),
    );
  }
}
