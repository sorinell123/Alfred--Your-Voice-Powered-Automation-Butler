// lib/services/macro_storage_service.dart

import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:alfred/models/macro.dart';

class MacroStorageService {
  static const String _macrosKey = 'saved_macros';
  static const String _lastSpeedKey = 'last_playback_speed';

  Future<void> saveMacro(Macro macro) async {
    final prefs = await SharedPreferences.getInstance();
    List<String> macros = prefs.getStringList(_macrosKey) ?? [];
    macros.add(jsonEncode(macro.toJson()));
    await prefs.setStringList(_macrosKey, macros);
  }

  Future<List<Macro>> loadMacros() async {
    final prefs = await SharedPreferences.getInstance();
    List<String> macros = prefs.getStringList(_macrosKey) ?? [];
    return macros.map((string) => Macro.fromJson(jsonDecode(string))).toList();
  }

  Future<void> deleteMacro(String id) async {
    final prefs = await SharedPreferences.getInstance();
    List<String> macros = prefs.getStringList(_macrosKey) ?? [];
    macros.removeWhere((macro) => Macro.fromJson(jsonDecode(macro)).id == id);
    await prefs.setStringList(_macrosKey, macros);
  }

  Future<void> saveLastPlaybackSpeed(double speed) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble(_lastSpeedKey, speed);
  }

  Future<double> getLastPlaybackSpeed() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getDouble(_lastSpeedKey) ?? 1.0;
  }
}
