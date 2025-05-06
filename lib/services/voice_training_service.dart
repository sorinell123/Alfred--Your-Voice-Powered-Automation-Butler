import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class VoiceProfileStatus {
  final int sampleCount;
  final bool trained;
  final int requiredSamples;

  VoiceProfileStatus({
    required this.sampleCount,
    required this.trained,
    required this.requiredSamples,
  });

  bool get isComplete => sampleCount >= requiredSamples;
  int get remainingSamples => requiredSamples - sampleCount;
  double get progress => sampleCount / requiredSamples;

  factory VoiceProfileStatus.fromMap(Map<Object?, Object?> map) {
    try {
      return VoiceProfileStatus(
        sampleCount: (map['sampleCount'] as num?)?.toInt() ?? 0,
        trained: map['trained'] as bool? ?? false,
        requiredSamples: (map['requiredSamples'] as num?)?.toInt() ?? 5,
      );
    } catch (e) {
      debugPrint("Error parsing profile status map: $e");
      debugPrint("Received map: $map");
      return VoiceProfileStatus(
        sampleCount: 0,
        trained: false,
        requiredSamples: 5,
      );
    }
  }

  @override
  String toString() {
    return 'VoiceProfileStatus(sampleCount: $sampleCount, trained: $trained, requiredSamples: $requiredSamples)';
  }
}

class VoiceTrainingException implements Exception {
  final String message;
  final String? details;

  VoiceTrainingException(this.message, [this.details]);

  @override
  String toString() {
    if (details != null) {
      return 'VoiceTrainingException: $message\nDetails: $details';
    }
    return 'VoiceTrainingException: $message';
  }
}

// Move typedefs outside the class
typedef TrainingProgressCallback = void Function(
    int samplesCollected, int totalRequired);
typedef NextPhraseCallback = void Function(String phrase);
typedef TrainingErrorCallback = void Function(String error);
typedef TrainingCompleteCallback = void Function();

class VoiceTrainingService {
  static const MethodChannel _platform =
      MethodChannel('com.example.alfred/voice_training');

  // Add callback properties
  TrainingProgressCallback? onTrainingProgress;
  NextPhraseCallback? onNextPhrase;
  TrainingErrorCallback? onTrainingError;
  TrainingCompleteCallback? onTrainingComplete;

  VoiceTrainingService() {
    _platform.setMethodCallHandler(_handleMethod);
  }

  Future<dynamic> _handleMethod(MethodCall call) async {
    try {
      debugPrint(
          'Received method call: ${call.method} with arguments: ${call.arguments}');
      switch (call.method) {
        case 'onTrainingProgress':
          if (onTrainingProgress != null && call.arguments is Map) {
            final map = call.arguments as Map;
            debugPrint(
                'Training progress: ${map['samplesCollected']}/${map['totalRequired']}');
            onTrainingProgress!(
              map['samplesCollected'] as int,
              map['totalRequired'] as int,
            );
          }
          break;

        case 'onNextPhrase':
          if (onNextPhrase != null && call.arguments is String) {
            onNextPhrase!(call.arguments as String);
          }
          break;

        case 'onTrainingError':
          if (onTrainingError != null && call.arguments is String) {
            onTrainingError!(call.arguments as String);
          }
          break;

        case 'onTrainingComplete':
          if (onTrainingComplete != null) {
            onTrainingComplete!();
          }
          break;
      }
    } catch (e) {
      debugPrint('Error handling method call ${call.method}: $e');
    }
  }

  Future<String> getCurrentTrainingPhrase() async {
    try {
      final String phrase =
          await _platform.invokeMethod('getCurrentTrainingPhrase');
      if (phrase.isEmpty) {
        throw VoiceTrainingException('Received empty training phrase');
      }
      return phrase;
    } on PlatformException catch (e) {
      throw VoiceTrainingException(
        'Failed to get training phrase',
        e.message,
      );
    }
  }

  Future<bool> startProfileTraining() async {
    try {
      final status = await getProfileStatus();
      if (status.isComplete) {
        throw VoiceTrainingException(
            'Voice profile training is already complete');
      }

      final bool result = await _platform.invokeMethod('startProfileTraining');
      if (!result) {
        throw VoiceTrainingException('Failed to start voice profile training');
      }
      return true;
    } on PlatformException catch (e) {
      throw VoiceTrainingException(
        'Failed to start profile training',
        e.message,
      );
    }
  }

  Future<bool> stopRecording() async {
    try {
      final bool result = await _platform.invokeMethod('stopRecording');
      if (!result) {
        throw VoiceTrainingException('Failed to stop recording');
      }
      return true;
    } on PlatformException catch (e) {
      throw VoiceTrainingException(
        'Failed to stop recording',
        e.message,
      );
    }
  }

  Future<VoiceProfileStatus> getProfileStatus() async {
    try {
      final dynamic result = await _platform.invokeMethod('getProfileStatus');
      debugPrint(
          "Received profile status result: $result (${result.runtimeType})");

      if (result is Map<Object?, Object?>) {
        final status = VoiceProfileStatus.fromMap(result);
        debugPrint("Successfully parsed profile status: $status");
        return status;
      } else {
        throw VoiceTrainingException(
          'Invalid profile status response',
          'Expected Map, got ${result.runtimeType}',
        );
      }
    } on PlatformException catch (e) {
      throw VoiceTrainingException(
        'Failed to get profile status',
        e.message,
      );
    } catch (e) {
      if (e is VoiceTrainingException) rethrow;
      throw VoiceTrainingException(
          'Unexpected error getting profile status', e.toString());
    }
  }

  Future<bool> resetProfile() async {
    try {
      final bool result = await _platform.invokeMethod('resetProfile');
      if (!result) {
        throw VoiceTrainingException('Failed to reset voice profile');
      }
      return true;
    } on PlatformException catch (e) {
      throw VoiceTrainingException(
        'Failed to reset profile',
        e.message,
      );
    }
  }

  Future<bool> isProfileTrained() async {
    try {
      final status = await getProfileStatus();
      return status.trained;
    } on PlatformException catch (e) {
      throw VoiceTrainingException(
        'Failed to check profile status',
        e.message,
      );
    }
  }

  String getTrainingProgress(VoiceProfileStatus status) {
    if (status.isComplete) {
      return 'Voice profile training complete';
    }
    return 'Recording ${status.sampleCount + 1} of ${status.requiredSamples}';
  }

  String getRecordingDuration() {
    return '4.5'; // Matches RECORDING_DURATION in VoiceTrainingState.kt
  }
}
