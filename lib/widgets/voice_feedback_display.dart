import 'package:flutter/material.dart';

class VoiceFeedbackDisplay extends StatelessWidget {
  final bool isListening;
  final bool isInCommandMode;
  final String voiceStatus;
  final String wakeWordFeedback;
  final String commandFeedback;

  const VoiceFeedbackDisplay({
    super.key,
    required this.isListening,
    required this.isInCommandMode,
    required this.voiceStatus,
    required this.wakeWordFeedback,
    required this.commandFeedback,
  });

  @override
  Widget build(BuildContext context) {
    if (!isListening) return const SizedBox.shrink();

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        // Status Bar
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
          color: isInCommandMode ? Colors.green.shade100 : Colors.blue.shade100,
          child: Text(
            isInCommandMode ? 'Command Mode' : 'Listening for "Hey Alfred"',
            style: TextStyle(
              fontWeight: FontWeight.bold,
              color: isInCommandMode
                  ? Colors.green.shade900
                  : Colors.blue.shade900,
            ),
            textAlign: TextAlign.center,
          ),
        ),

        // Feedback Display
        Container(
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            border: Border.all(
              color: isInCommandMode ? Colors.green : Colors.blue,
              width: 2,
            ),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                isInCommandMode
                    ? 'Speak your command'
                    : 'Say "Hey Alfred" to start',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: isInCommandMode ? Colors.green : Colors.blue,
                ),
              ),
              const SizedBox(height: 16),
              Text(
                'Heard:',
                style: TextStyle(
                  fontSize: 16,
                  color: Colors.grey[600],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                isInCommandMode ? commandFeedback : wakeWordFeedback,
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                ),
                textAlign: TextAlign.center,
              ),
              if (voiceStatus.isNotEmpty) ...[
                const SizedBox(height: 16),
                Text(
                  voiceStatus,
                  style: TextStyle(
                    fontSize: 14,
                    color: Colors.grey[600],
                  ),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}
