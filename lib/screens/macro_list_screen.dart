import 'package:flutter/material.dart';
import 'package:alfred/models/macro.dart';
import 'package:alfred/services/macro_storage_service.dart';
import 'package:alfred/services/playback_service.dart';

class MacroListScreen extends StatefulWidget {
  final MacroStorageService macroStorageService;
  final PlaybackService playbackService;

  const MacroListScreen({
    Key? key,
    required this.macroStorageService,
    required this.playbackService,
  }) : super(key: key);

  @override
  _MacroListScreenState createState() => _MacroListScreenState();
}

class _MacroListScreenState extends State<MacroListScreen> {
  List<Macro> _macros = [];
  double _playbackSpeed = 1.0;

  @override
  void initState() {
    super.initState();
    _loadMacros();
    _loadLastPlaybackSpeed();
  }

  Future<void> _loadMacros() async {
    final macros = await widget.macroStorageService.loadMacros();
    setState(() {
      _macros = macros;
    });
  }

  Future<void> _loadLastPlaybackSpeed() async {
    final lastSpeed = await widget.macroStorageService.getLastPlaybackSpeed();
    setState(() {
      _playbackSpeed = lastSpeed;
    });
    widget.playbackService.setPlaybackSpeed(lastSpeed);
  }

  void _playMacro(Macro macro) async {
    await widget.playbackService.loadActions(macro.actions);
    await widget.playbackService.startPlayback(speed: _playbackSpeed);
  }

  void _deleteMacro(Macro macro) async {
    await widget.macroStorageService.deleteMacro(macro.id);
    await _loadMacros();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Saved Macros'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: Row(
              children: [
                Text('Playback Speed: ${_playbackSpeed.toStringAsFixed(1)}x'),
                Expanded(
                  child: Slider(
                    value: _playbackSpeed,
                    min: 0.5,
                    max: 10.0,
                    divisions: 19,
                    label: '${_playbackSpeed.toStringAsFixed(1)}x',
                    onChanged: (value) async {
                      setState(() {
                        _playbackSpeed = value;
                      });
                      widget.playbackService.setPlaybackSpeed(value);
                      await widget.macroStorageService
                          .saveLastPlaybackSpeed(value);
                    },
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView.builder(
              itemCount: _macros.length,
              itemBuilder: (context, index) {
                final macro = _macros[index];
                return ListTile(
                  title: Text(macro.name),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        icon: Icon(Icons.play_arrow),
                        onPressed: () => _playMacro(macro),
                      ),
                      IconButton(
                        icon: Icon(Icons.delete),
                        onPressed: () => _deleteMacro(macro),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
