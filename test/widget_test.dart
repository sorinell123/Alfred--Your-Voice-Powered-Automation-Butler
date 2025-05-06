import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:alfred/main.dart';
import 'package:alfred/services/accessibility_service.dart';
import 'package:alfred/services/macro_storage_service.dart';
import 'package:alfred/services/recorder_service.dart';
import 'package:alfred/services/playback_service.dart';
import 'package:alfred/services/floating_button_service.dart';

void main() {
  testWidgets('App initialization test', (WidgetTester tester) async {
    // Build our app and trigger a frame.
    await tester.pumpWidget(const AlfredApp());

    // Verify that the app title is displayed
    expect(find.text('Alfred'), findsOneWidget);

    // Verify that the main action buttons are present
    expect(find.text('Start Recording'), findsOneWidget);
    expect(find.text('Open Accessibility Settings'), findsOneWidget);
  });
}
