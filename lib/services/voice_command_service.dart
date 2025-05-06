import 'package:flutter/services.dart';

class VoiceCommandService {
  static const _channel = MethodChannel('com.example.alfred/voice');

  // Callback functions for voice events
  Function(String)? onVoiceResult;
  Function(String)? onVoiceStatus;
  Function(String)? onVoiceError;
  Function(String)? onSpeechRecognized;
  Function(String)? onPartialSpeechRecognized;
  Function(String)? onPartialCommandRecognized;
  Function()? onWakeWordDetected;
  Function()? onListeningForCommand;
  Function(String)? onWakeWordFeedback;
  Function(String)? onCommandFeedback;

  VoiceCommandService() {
    _setupMethodCallHandler();
  }

  void _setupMethodCallHandler() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onVoiceStatus':
          onVoiceStatus?.call(call.arguments as String);
          break;
        case 'onVoiceError':
          onVoiceError?.call(call.arguments as String);
          break;
        case 'onCommandReceived':
          onVoiceResult?.call(call.arguments as String);
          break;
        case 'onWakeWordDetected':
          onWakeWordDetected?.call();
          break;
        case 'onListeningForCommand':
          onListeningForCommand?.call();
          break;
        case 'onListeningFeedback':
          final args = call.arguments as Map;
          final mode = args['mode'] as String;
          final text = args['text'] as String;

          if (mode == 'wake_word') {
            onWakeWordFeedback?.call(text);
          } else if (mode == 'command') {
            onCommandFeedback?.call(text);
          }
          break;
      }
    });
  }

  Future<bool> startListening() async {
    try {
      print('Starting voice recognition');
      final result = await _channel.invokeMethod('startListening');
      return result ?? false;
    } catch (e) {
      print('Error starting voice recognition: $e');
      return false;
    }
  }

  Future<void> stopListening() async {
    try {
      await _channel.invokeMethod('stopListening');
    } catch (e) {
      print('Error stopping voice recognition: $e');
    }
  }

  Future<void> updateCommands(List<String> commands) async {
    try {
      print('Updating available commands: $commands');
      final result = await _channel.invokeMethod('updateCommands', {
        'commands': commands,
      });
      print('Commands updated: $result');
    } catch (e) {
      print('Error updating commands: $e');
    }
  }
}
