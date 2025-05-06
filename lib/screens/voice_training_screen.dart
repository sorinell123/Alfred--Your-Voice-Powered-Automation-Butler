import 'package:flutter/material.dart';
import 'package:alfred/services/voice_training_service.dart';
import 'package:alfred/services/macro_storage_service.dart';

class VoiceTrainingScreen extends StatefulWidget {
  final MacroStorageService macroStorageService;
  final VoiceTrainingService voiceTrainingService;

  const VoiceTrainingScreen({
    super.key,
    required this.macroStorageService,
    required this.voiceTrainingService,
  });

  @override
  State<VoiceTrainingScreen> createState() => _VoiceTrainingScreenState();
}

class _VoiceTrainingScreenState extends State<VoiceTrainingScreen> {
  bool _isRecording = false;
  bool _isProcessing = false;
  String _recordingStatus = '';
  String _currentPhrase = '';
  VoiceProfileStatus _profileStatus = VoiceProfileStatus(
    sampleCount: 0,
    trained: false,
    requiredSamples: 5,
  );

  @override
  void initState() {
    super.initState();

    // Set up callbacks
    widget.voiceTrainingService.onTrainingProgress = (samples, total) {
      if (mounted) {
        setState(() {
          _profileStatus = VoiceProfileStatus(
            sampleCount: samples,
            trained: samples >= total,
            requiredSamples: total,
          );
        });
      }
    };

    widget.voiceTrainingService.onNextPhrase = (phrase) {
      if (mounted) {
        setState(() {
          _currentPhrase = phrase;
          _isProcessing = false;
          _recordingStatus = '';
        });
      }
    };

    widget.voiceTrainingService.onTrainingError = (error) {
      if (mounted) {
        setState(() {
          _isRecording = false;
          _isProcessing = false;
          _recordingStatus = '';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Training error: $error'),
            backgroundColor: Colors.red,
          ),
        );
      }
    };

    widget.voiceTrainingService.onTrainingComplete = () {
      if (mounted) {
        setState(() {
          _isRecording = false;
          _isProcessing = false;
          _recordingStatus = '';
          _profileStatus = VoiceProfileStatus(
            sampleCount: _profileStatus.requiredSamples,
            trained: true,
            requiredSamples: _profileStatus.requiredSamples,
          );
        });
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Voice profile training complete!'),
            backgroundColor: Colors.green,
          ),
        );
      }
    };

    _loadProfileStatus();
    _loadCurrentPhrase();
  }

  @override
  void dispose() {
    // Clear callbacks
    widget.voiceTrainingService.onTrainingProgress = null;
    widget.voiceTrainingService.onNextPhrase = null;
    widget.voiceTrainingService.onTrainingError = null;
    widget.voiceTrainingService.onTrainingComplete = null;
    super.dispose();
  }

  Future<void> _loadCurrentPhrase() async {
    try {
      final phrase =
          await widget.voiceTrainingService.getCurrentTrainingPhrase();
      if (mounted) {
        setState(() {
          _currentPhrase = phrase;
        });
      }
    } on VoiceTrainingException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error loading phrase: ${e.message}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _loadProfileStatus() async {
    try {
      final status = await widget.voiceTrainingService.getProfileStatus();
      if (mounted) {
        setState(() {
          _profileStatus = status;
        });
      }
    } on VoiceTrainingException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error loading profile status: ${e.message}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  void _showTrainingInstructions() {
    showDialog(
      context: context,
      barrierDismissible: !_isRecording,
      builder: (context) => AlertDialog(
        title: const Text('Voice Profile Training'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Training your voice profile helps Alfred recognize your voice for all commands.',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 16),
              const Text('Tips for best results:'),
              const SizedBox(height: 8),
              const Text('• Speak clearly and naturally'),
              const Text('• Keep background noise minimal'),
              const Text('• Hold phone 6-12 inches from mouth'),
              const Text('• Use your normal speaking voice'),
              Text(
                  '• Read the entire phrase within ${widget.voiceTrainingService.getRecordingDuration()} seconds'),
              const SizedBox(height: 16),
              Text(
                widget.voiceTrainingService.getTrainingProgress(_profileStatus),
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              const Text('Please read the following phrase:'),
              const SizedBox(height: 8),
              Text(
                _currentPhrase,
                style: const TextStyle(
                  fontStyle: FontStyle.italic,
                  color: Colors.blue,
                  fontSize: 16,
                  height: 1.5,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed:
                _isRecording ? null : () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: _isRecording
                ? null
                : () {
                    Navigator.pop(context, true);
                    _startRecording();
                  },
            child: const Text('Start Recording'),
          ),
        ],
      ),
    );
  }

  Future<void> _startRecording() async {
    if (_isRecording || _isProcessing) return;

    try {
      setState(() {
        _recordingStatus = 'Preparing to record...';
        _isProcessing = true;
      });

      if (await widget.voiceTrainingService.startProfileTraining()) {
        setState(() {
          _isRecording = true;
          _isProcessing = false;
          _recordingStatus = 'Recording... Speak now';
        });

        // Show countdown
        final duration =
            double.parse(widget.voiceTrainingService.getRecordingDuration());
        final seconds = duration.ceil();

        for (int i = seconds; i > 0; i--) {
          if (!mounted || !_isRecording) return;
          setState(() {
            _recordingStatus =
                'Recording... $i seconds left\n\n$_currentPhrase';
          });
          await Future.delayed(const Duration(milliseconds: 1000));
        }

        await _stopRecording();
      } else {
        if (mounted) {
          setState(() {
            _recordingStatus = '';
            _isProcessing = false;
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                  'Failed to start recording. Please check microphone permissions.'),
              backgroundColor: Colors.red,
            ),
          );
        }
      }
    } on VoiceTrainingException catch (e) {
      if (mounted) {
        setState(() {
          _isRecording = false;
          _isProcessing = false;
          _recordingStatus = '';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Recording error: ${e.message}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _stopRecording() async {
    if (!_isRecording) return;

    try {
      setState(() {
        _isRecording = false;
        _isProcessing = true;
        _recordingStatus = 'Processing recording...';
      });

      await widget.voiceTrainingService.stopRecording();
      await _loadProfileStatus();
      await _loadCurrentPhrase(); // Load next phrase

      if (mounted) {
        setState(() {
          _isProcessing = false;
          _recordingStatus = '';
        });

        if (!_profileStatus.trained) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                'Sample recorded! ${_profileStatus.remainingSamples} more ${_profileStatus.remainingSamples == 1 ? 'sample' : 'samples'} needed.',
              ),
              backgroundColor: Colors.green,
            ),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                  'Voice profile training complete! You can now use voice commands.'),
              backgroundColor: Colors.green,
            ),
          );
        }
      }
    } on VoiceTrainingException catch (e) {
      if (mounted) {
        setState(() {
          _isRecording = false;
          _isProcessing = false;
          _recordingStatus = '';
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error stopping recording: ${e.message}'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _resetProfile() async {
    if (_isRecording || _isProcessing) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Reset Voice Profile'),
        content: const Text(
          'Are you sure you want to reset your voice profile?\n\n'
          'This will delete all training samples and you\'ll need to train your voice again.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor: Colors.red,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Reset'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      try {
        setState(() {
          _isProcessing = true;
          _recordingStatus = 'Resetting profile...';
        });

        await widget.voiceTrainingService.resetProfile();
        await _loadProfileStatus();
        await _loadCurrentPhrase();

        if (mounted) {
          setState(() {
            _isProcessing = false;
            _recordingStatus = '';
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Voice profile has been reset'),
            ),
          );
        }
      } on VoiceTrainingException catch (e) {
        if (mounted) {
          setState(() {
            _isProcessing = false;
            _recordingStatus = '';
          });
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Error resetting profile: ${e.message}'),
              backgroundColor: Colors.red,
            ),
          );
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return WillPopScope(
      onWillPop: () async => !_isRecording && !_isProcessing,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Voice Training'),
          automaticallyImplyLeading: !_isRecording && !_isProcessing,
        ),
        body: SingleChildScrollView(
          physics: _isRecording || _isProcessing
              ? const NeverScrollableScrollPhysics()
              : null,
          child: Column(
            children: [
              if (_recordingStatus.isNotEmpty)
                Container(
                  color: Colors.blue.shade100,
                  padding: const EdgeInsets.all(16),
                  width: double.infinity,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          if (_isRecording)
                            Container(
                              width: 12,
                              height: 12,
                              margin: const EdgeInsets.only(right: 8),
                              decoration: BoxDecoration(
                                color: Colors.red,
                                borderRadius: BorderRadius.circular(6),
                              ),
                            ),
                          Expanded(
                            child: Text(
                              _recordingStatus,
                              style:
                                  const TextStyle(fontWeight: FontWeight.bold),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(
                      Icons.record_voice_over,
                      size: 80,
                      color: Colors.blue,
                    ),
                    const SizedBox(height: 24),
                    const Text(
                      'Voice Profile Training',
                      style: TextStyle(
                        fontSize: 24,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _profileStatus.trained
                          ? 'Your voice profile is ready to use'
                          : 'Train Alfred to recognize your voice',
                      style: TextStyle(
                        fontSize: 16,
                        color: Colors.grey[600],
                      ),
                    ),
                    const SizedBox(height: 32),
                    LinearProgressIndicator(
                      value: _profileStatus.progress,
                      backgroundColor: Colors.grey[300],
                      color: _profileStatus.trained ? Colors.green : null,
                      minHeight: 8,
                    ),
                    const SizedBox(height: 16),
                    Text(
                      _profileStatus.trained
                          ? 'Training complete'
                          : '${_profileStatus.sampleCount} of ${_profileStatus.requiredSamples} voice samples recorded',
                      style: TextStyle(
                        fontSize: 16,
                        color: _profileStatus.trained ? Colors.green : null,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 32),
                    if (!_profileStatus.trained) ...[
                      const Text(
                        'Next training phrase:',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.blue.shade50,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Text(
                          _currentPhrase,
                          style: const TextStyle(
                            fontSize: 16,
                            height: 1.5,
                            color: Colors.blue,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ),
                      const SizedBox(height: 32),
                    ],
                    Wrap(
                      spacing: 16,
                      runSpacing: 16,
                      alignment: WrapAlignment.center,
                      children: [
                        if (_profileStatus.sampleCount > 0)
                          OutlinedButton.icon(
                            onPressed: (_isRecording || _isProcessing)
                                ? null
                                : _resetProfile,
                            icon: const Icon(Icons.refresh),
                            label: const Text('Reset Profile'),
                            style: OutlinedButton.styleFrom(
                              foregroundColor: Colors.red,
                            ),
                          ),
                        ElevatedButton.icon(
                          onPressed: (_isRecording || _isProcessing)
                              ? null
                              : _showTrainingInstructions,
                          icon: Icon(_isRecording ? Icons.stop : Icons.mic),
                          label: Text(_isRecording
                              ? 'Recording...'
                              : _isProcessing
                                  ? 'Processing...'
                                  : 'Record Sample'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor:
                                _profileStatus.trained ? Colors.green : null,
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
