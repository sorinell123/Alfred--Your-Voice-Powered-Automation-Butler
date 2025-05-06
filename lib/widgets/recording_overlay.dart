import 'package:flutter/material.dart';

class RecordingOverlay extends StatelessWidget {
  final bool isRecording;

  const RecordingOverlay({Key? key, required this.isRecording}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Visibility(
      visible: isRecording,
      child: Container(
        color: Colors.red.withOpacity(0.3),
        padding: EdgeInsets.all(8),
        child: Text(
          'Recording in progress',
          style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
          textAlign: TextAlign.center,
        ),
      ),
    );
  }
}