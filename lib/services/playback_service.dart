import 'package:flutter/services.dart';
import 'package:alfred/models/macro.dart';

class PlaybackService {
  static const platform = const MethodChannel('com.example.alfred/playback');
  bool _isPlaying = false;
  List<Map<String, dynamic>> _actions = [];
  double _speed = 1.0;

  PlaybackService() {
    // Initialize the channel and verify it's working
    _verifyChannel();
  }

  Future<void> _verifyChannel() async {
    try {
      await platform.invokeMethod('playbackActions', {
        'actions': [],
        'speed': 1.0,
      });
    } catch (e) {
      print("Playback channel verification: $e");
      // Expected to fail with empty actions, but should verify channel exists
    }
  }

  Future<void> loadActions(List<Map<String, dynamic>> actions) async {
    _actions = List<Map<String, dynamic>>.from(actions);
    print("Actions loaded: ${_actions.length}");
  }

  Future<void> playMacro(Macro macro) async {
    await loadActions(macro.actions);
    await startPlayback();
  }

  Future<void> startPlayback({double speed = 1.0}) async {
    if (!_isPlaying && _actions.isNotEmpty) {
      _isPlaying = true;
      _speed = speed;
      try {
        print(
            "Starting playback with ${_actions.length} actions at speed $_speed");
        final bool result = await platform.invokeMethod('playbackActions', {
          'actions': _actions
              .map((action) => Map<String, dynamic>.from(action))
              .toList(),
          'speed': _speed,
        });
        print("Playback started: $result");
      } catch (e) {
        print("Error during playback: $e");
        _isPlaying = false;
        rethrow; // Rethrow to let UI handle the error
      } finally {
        _isPlaying = false;
        print("Playback finished");
      }
    }
  }

  Future<void> setPlaybackSpeed(double speed) async {
    _speed = speed;
    if (_isPlaying) {
      try {
        await platform.invokeMethod('setPlaybackSpeed', {'speed': _speed});
      } catch (e) {
        print("Error setting playback speed: $e");
        rethrow;
      }
    }
  }

  Future<void> stopPlayback() async {
    if (_isPlaying) {
      try {
        await platform.invokeMethod('stopPlayback');
        _isPlaying = false;
      } catch (e) {
        print("Error stopping playback: $e");
        rethrow;
      }
    }
  }

  bool get isPlaying => _isPlaying;
  double get speed => _speed;
}
