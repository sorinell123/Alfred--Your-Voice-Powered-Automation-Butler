import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:alfred/screens/macro_list_screen.dart';
import 'package:alfred/screens/voice_training_screen.dart';
import 'package:alfred/services/accessibility_service.dart';
import 'package:alfred/services/macro_storage_service.dart';
import 'package:alfred/services/recorder_service.dart';
import 'package:alfred/services/playback_service.dart';
import 'package:alfred/services/floating_button_service.dart';
import 'package:alfred/services/voice_command_service.dart';
import 'package:alfred/services/voice_training_service.dart';
import 'package:alfred/widgets/recording_overlay.dart';
import 'package:alfred/models/macro.dart';

class HomeScreen extends StatefulWidget {
  final AccessibilityService accessibilityService;
  final MacroStorageService macroStorageService;
  final RecorderService recorderService;
  final PlaybackService playbackService;
  final FloatingButtonService floatingButtonService;

  const HomeScreen({
    super.key,
    required this.accessibilityService,
    required this.macroStorageService,
    required this.recorderService,
    required this.playbackService,
    required this.floatingButtonService,
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  bool _isAccessibilityServiceEnabled = false;
  bool _isFloatingButtonVisible = false;
  bool _isListening = false;
  bool _isInCommandMode = false;
  String _voiceStatus = '';
  String _recognizedSpeech = '';
  String _partialSpeech = '';
  String _partialCommand = '';
  String _wakeWordFeedback = '';
  String _commandFeedback = '';
  late Timer _timer;
  final _voiceCommandService = VoiceCommandService();
  final _voiceTrainingService = VoiceTrainingService();
  static const platform = MethodChannel('com.example.alfred/recorder');

  @override
  void initState() {
    super.initState();
    _checkAccessibilityService();
    _timer = Timer.periodic(const Duration(seconds: 5), (timer) {
      _checkAccessibilityService();
    });
    _setupMethodChannel();
    _setupVoiceCommandService();
    _updateAvailableCommands();
    platform.setMethodCallHandler((call) async {
      if (call.method == 'toggleRecording') {
        _toggleRecording();
      }
    });
  }

  @override
  void dispose() {
    _timer.cancel();
    super.dispose();
  }

  Future<void> _updateAvailableCommands() async {
    final macros = await widget.macroStorageService.loadMacros();
    final commands = macros.map((m) => m.name.toLowerCase()).toList();
    print('Updating available commands: $commands');
    await _voiceCommandService.updateCommands(commands);
  }

  void _setupVoiceCommandService() {
    _voiceCommandService.onVoiceResult = (String command) {
      _handleVoiceCommand(command);
    };

    _voiceCommandService.onVoiceStatus = (String status) {
      if (mounted) {
        setState(() {
          _voiceStatus = status;
          if (status == 'ready') {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Ready for voice command')),
            );
          }
        });
      }
    };

    _voiceCommandService.onVoiceError = (String error) {
      if (mounted) {
        setState(() {
          _isListening = false;
          _isInCommandMode = false;
          _voiceStatus = '';
          _recognizedSpeech = '';
          _partialSpeech = '';
          _partialCommand = '';
          _wakeWordFeedback = '';
          _commandFeedback = '';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Voice recognition error: $error')),
        );
      }
    };

    _voiceCommandService.onSpeechRecognized = (String speech) {
      if (mounted) {
        setState(() {
          _recognizedSpeech = speech;
        });
      }
    };

    _voiceCommandService.onPartialSpeechRecognized = (String partial) {
      if (mounted) {
        setState(() {
          _partialSpeech = partial;
        });
      }
    };

    _voiceCommandService.onPartialCommandRecognized = (String partial) {
      if (mounted) {
        setState(() {
          _partialCommand = partial;
        });
      }
    };

    _voiceCommandService.onWakeWordDetected = () {
      if (mounted) {
        setState(() {
          _isInCommandMode = true;
          _partialCommand = '';
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
                content:
                    Text('Wake word detected! What would you like me to do?')),
          );
        });
      }
    };

    _voiceCommandService.onListeningForCommand = () {
      if (mounted) {
        setState(() {
          _isInCommandMode = true;
          _wakeWordFeedback = '';
          _commandFeedback = 'Listening for command...';
        });
      }
    };

    _voiceCommandService.onWakeWordFeedback = (String text) {
      if (mounted) {
        setState(() {
          _wakeWordFeedback = text;
        });
      }
    };

    _voiceCommandService.onCommandFeedback = (String text) {
      if (mounted) {
        setState(() {
          _commandFeedback = text;
        });
      }
    };
  }

  Future<void> _handleVoiceCommand(String command) async {
    command = command.toLowerCase().trim();
    print('Looking for macro: $command');

    final macros = await widget.macroStorageService.loadMacros();
    print(
        'Available macros: ${macros.map((m) => m.name.toLowerCase()).toList()}');

    // Find best matching macro
    Macro? bestMatch;
    double bestScore = 0;

    for (var macro in macros) {
      double similarity = _calculateSimilarity(macro.name, command);
      print('Similarity between "${macro.name}" and "$command": $similarity');

      if (similarity > bestScore) {
        bestScore = similarity;
        bestMatch = macro;
      }
    }

    // If we have a good match, run it
    if (bestMatch != null && bestScore > 0.6) {
      print('Found matching macro: ${bestMatch.name} (score: $bestScore)');
      await widget.playbackService.playMacro(bestMatch);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Running macro: ${bestMatch.name}'),
            action: SnackBarAction(
              label: 'Wrong Macro?',
              onPressed: () {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                      content:
                          Text('Try saying exactly: "${bestMatch?.name}"')),
                );
              },
            ),
          ),
        );
      }
    } else {
      print('No matching macro found for: $command');
      if (mounted) {
        final suggestions = macros
            .map((m) => m.name)
            .where((name) => _calculateSimilarity(name, command) > 0.4)
            .take(3)
            .toList();

        String message = 'No matching macro found for "$command"';
        if (suggestions.isNotEmpty) {
          message += '\nDid you mean: ${suggestions.join(", ")}?';
        }

        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(message),
            action: SnackBarAction(
              label: 'Train Voice',
              onPressed: () => _openVoiceTraining(),
            ),
          ),
        );
      }
    }
  }

  void _setupMethodChannel() {
    platform.setMethodCallHandler((call) async {
      if (!mounted) return;

      switch (call.method) {
        case 'updateRecordingState':
          setState(() {
            widget.recorderService.updateRecordingState(call.arguments as bool);
          });
          break;
        case 'promptSaveRecording':
          _showNativeSaveMacroDialog();
          break;
        case 'toggleRecording':
          _toggleRecording();
          break;
      }
    });
  }

  Future<void> _toggleVoiceCommand() async {
    if (_isListening) {
      await _voiceCommandService.stopListening();
      setState(() {
        _isListening = false;
        _isInCommandMode = false;
      });
    } else {
      await _updateAvailableCommands();
      final bool started = await _voiceCommandService.startListening();
      if (mounted) {
        if (started) {
          setState(() => _isListening = true);
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Listening for wake word...')),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Failed to start voice recognition')),
          );
        }
      }
    }
  }

  void _openVoiceTraining() {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => VoiceTrainingScreen(
          macroStorageService: widget.macroStorageService,
          voiceTrainingService: _voiceTrainingService,
        ),
      ),
    );
  }

  double _calculateSimilarity(String s1, String s2) {
    if (s1.isEmpty || s2.isEmpty) return 0.0;

    s1 = s1.toLowerCase();
    s2 = s2.toLowerCase();

    // Exact match
    if (s1 == s2) return 1.0;

    // One string contains the other
    if (s1.contains(s2) || s2.contains(s1)) {
      return 0.9;
    }

    // Calculate word-by-word similarity
    var words1 = s1.split(' ');
    var words2 = s2.split(' ');

    int matches = 0;
    for (var w1 in words1) {
      for (var w2 in words2) {
        // Check for exact word match or high similarity
        if (w1 == w2 || _levenshteinDistance(w1, w2) <= 2) {
          matches++;
          break;
        }
      }
    }

    // Calculate overall similarity
    double wordSimilarity = matches / words1.length;
    double lengthRatio = s1.length / s2.length;
    if (lengthRatio > 1) lengthRatio = 1 / lengthRatio;

    return (wordSimilarity * 0.7 + lengthRatio * 0.3);
  }

  int _levenshteinDistance(String s1, String s2) {
    s1 = s1.toLowerCase();
    s2 = s2.toLowerCase();

    List<List<int>> dp = List.generate(
      s1.length + 1,
      (i) => List.generate(s2.length + 1, (j) => j == 0 ? i : (i == 0 ? j : 0)),
    );

    for (int i = 1; i <= s1.length; i++) {
      for (int j = 1; j <= s2.length; j++) {
        if (s1[i - 1] == s2[j - 1]) {
          dp[i][j] = dp[i - 1][j - 1];
        } else {
          dp[i][j] = [
            dp[i - 1][j] + 1, // deletion
            dp[i][j - 1] + 1, // insertion
            dp[i - 1][j - 1] + 1, // substitution
          ].reduce((a, b) => a < b ? a : b);
        }
      }
    }

    return dp[s1.length][s2.length];
  }

  Future<void> _checkAccessibilityService() async {
    if (!mounted) return;
    final isEnabled = await widget.accessibilityService.isServiceEnabled();
    setState(() {
      _isAccessibilityServiceEnabled = isEnabled;
    });
    print("Accessibility Service Status: $_isAccessibilityServiceEnabled");
  }

  Future<void> _toggleRecording() async {
    if (!mounted) return;

    if (widget.recorderService.isRecording) {
      final actions = await widget.recorderService.stopRecording();
      print("Stopped recording. Actions: $actions");
      if (actions.isNotEmpty) {
        _showNativeSaveMacroDialog();
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No actions were recorded.')),
        );
      }
    } else {
      final started = await widget.recorderService.startRecording();
      if (started) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text('Recording started. Perform actions now.')),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to start recording.')),
        );
      }
    }
    setState(() {});
  }

  Future<void> _showNativeSaveMacroDialog() async {
    if (!mounted) return;

    try {
      final String? macroName =
          await platform.invokeMethod('showSaveMacroDialog');
      if (macroName != null && macroName.isNotEmpty) {
        final macro = widget.recorderService.createMacro(macroName);
        await widget.macroStorageService.saveMacro(macro);
        await _updateAvailableCommands();
      }
    } on PlatformException catch (e) {
      print("Failed to show save macro dialog: '${e.message}'.");
    }
  }

  void _toggleFloatingButton() async {
    if (!mounted) return;

    setState(() {
      _isFloatingButtonVisible = !_isFloatingButtonVisible;
    });
    if (_isFloatingButtonVisible) {
      await widget.floatingButtonService.showFloatingButton();
    } else {
      await widget.floatingButtonService.hideFloatingButton();
    }
  }

  void _openAccessibilitySettings() async {
    await widget.accessibilityService.openAccessibilitySettings();
  }

  String _getStatusText() {
    if (widget.recorderService.isRecording) {
      return 'Recording in progress...';
    }
    if (widget.playbackService.isPlaying) {
      return 'Playback in progress...';
    }
    if (_isListening) {
      if (_isInCommandMode) {
        if (_partialCommand.isNotEmpty) {
          return 'Command: $_partialCommand';
        }
        return 'Listening for command...';
      }
      if (_partialSpeech.isNotEmpty) {
        return 'Heard: $_partialSpeech';
      }
      if (_recognizedSpeech.isNotEmpty) {
        return 'Recognized: $_recognizedSpeech';
      }
      if (_voiceStatus.isNotEmpty) {
        return 'Voice Recognition: $_voiceStatus';
      }
      return 'Listening for wake word...';
    }
    if (!_isAccessibilityServiceEnabled) {
      return 'Accessibility Service Disabled';
    }
    return 'Ready';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Alfred'),
        actions: [
          IconButton(
            icon: Icon(_isListening
                ? (_isInCommandMode ? Icons.record_voice_over : Icons.mic)
                : Icons.mic_none),
            onPressed:
                _isAccessibilityServiceEnabled ? _toggleVoiceCommand : null,
            color: _isListening
                ? (_isInCommandMode ? Colors.green : Colors.red)
                : null,
          ),
          IconButton(
            icon: const Icon(Icons.record_voice_over),
            onPressed:
                _isAccessibilityServiceEnabled ? _openVoiceTraining : null,
            tooltip: 'Voice Training',
          ),
          IconButton(
            icon: const Icon(Icons.list),
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => MacroListScreen(
                    macroStorageService: widget.macroStorageService,
                    playbackService: widget.playbackService,
                  ),
                ),
              ).then((_) => _updateAvailableCommands());
            },
          ),
          IconButton(
            icon: Icon(_isFloatingButtonVisible
                ? Icons.visibility_off
                : Icons.visibility),
            onPressed: _toggleFloatingButton,
          ),
        ],
      ),
      body: Stack(
        children: [
          Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: <Widget>[
                // Voice feedback display
                if (_isListening)
                  Container(
                    margin: const EdgeInsets.symmetric(
                        horizontal: 20, vertical: 10),
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      border: Border.all(
                        color: _isInCommandMode ? Colors.green : Colors.blue,
                        width: 2,
                      ),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Column(
                      children: [
                        Text(
                          _isInCommandMode ? 'Command Mode' : 'Wake Word Mode',
                          style: TextStyle(
                            fontWeight: FontWeight.bold,
                            color:
                                _isInCommandMode ? Colors.green : Colors.blue,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          _isInCommandMode
                              ? _commandFeedback
                              : _wakeWordFeedback,
                          style: const TextStyle(fontSize: 18),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                ElevatedButton(
                  onPressed:
                      _isAccessibilityServiceEnabled ? _toggleRecording : null,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: widget.recorderService.isRecording
                        ? Colors.red
                        : Colors.green,
                    padding: const EdgeInsets.symmetric(
                        horizontal: 50, vertical: 20),
                    textStyle: const TextStyle(
                        fontSize: 24, fontWeight: FontWeight.bold),
                  ),
                  child: Text(widget.recorderService.isRecording
                      ? 'Stop Recording'
                      : 'Start Recording'),
                ),
                const SizedBox(height: 20),
                Text(
                  _getStatusText(),
                  style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        color: _isInCommandMode ? Colors.green : null,
                      ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 20),
                ElevatedButton(
                  onPressed: _openAccessibilitySettings,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.blue,
                    padding: const EdgeInsets.symmetric(
                        horizontal: 20, vertical: 10),
                  ),
                  child: const Text('Open Accessibility Settings'),
                ),
              ],
            ),
          ),
          RecordingOverlay(isRecording: widget.recorderService.isRecording),
          if (_isInCommandMode && _commandFeedback.isNotEmpty)
            Positioned(
              bottom: 100,
              left: 20,
              right: 20,
              child: Container(
                padding: EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.blue
                      .withOpacity(0.9), // Blue background for command mode
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _commandFeedback,
                  style: TextStyle(color: Colors.white),
                  textAlign: TextAlign.center,
                ),
              ),
            ),
          if (!_isInCommandMode && _wakeWordFeedback.isNotEmpty)
            Positioned(
              bottom: 100,
              left: 20,
              right: 20,
              child: Container(
                padding: EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.green
                      .withOpacity(0.9), // Green background for wake word mode
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  _wakeWordFeedback,
                  style: TextStyle(color: Colors.white),
                  textAlign: TextAlign.center,
                ),
              ),
            ),
        ],
      ),
    );
  }
}
