import 'package:flutter/services.dart';
import 'package:alfred/models/macro.dart';
import 'package:uuid/uuid.dart';
import 'action_storage.dart';

class RecorderService {
  static const platform = MethodChannel('com.example.alfred/recorder');
  final ActionStorage _actionStorage = ActionStorage();

  bool _isRecording = false;
  List<Map<String, dynamic>> _recordedActions = [];

  bool get isRecording => _isRecording;

  Future<bool> startRecording() async {
    if (!_isRecording) {
      try {
        final bool result = await platform.invokeMethod('startRecording');
        _isRecording = result;
        _recordedActions.clear();
        print("Recording started: $result");
        return result;
      } on PlatformException catch (e) {
        print("Failed to start recording: '${e.message}'.");
        return false;
      }
    }
    return false;
  }

  Future<List<Map<String, dynamic>>> stopRecording() async {
    if (_isRecording) {
      try {
        final List<dynamic> result =
            await platform.invokeMethod('stopRecording');
        _isRecording = false;
        _recordedActions =
            result.map((item) => Map<String, dynamic>.from(item)).toList();
        await _actionStorage.saveActions(_recordedActions);
        print("Recording stopped. Recorded actions: $_recordedActions");
        return _recordedActions;
      } on PlatformException catch (e) {
        print("Failed to stop recording: '${e.message}'.");
        return [];
      }
    }
    return [];
  }

  void updateRecordingState(bool isRecording) {
    _isRecording = isRecording;
  }

  Macro createMacro(String name) {
    return Macro(
      id: const Uuid().v4(),
      name: name,
      actions: List.from(_recordedActions),
    );
  }

  Future<List<Map<String, dynamic>>> loadRecordedActions() async {
    return await _actionStorage.loadActions();
  }

  Future<void> clearRecordedActions() async {
    await _actionStorage.clearActions();
    _recordedActions.clear();
  }

  List<Map<String, dynamic>> getRecordedActions() {
    return List.from(_recordedActions);
  }
}
