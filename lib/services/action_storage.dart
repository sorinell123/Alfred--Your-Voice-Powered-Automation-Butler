import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

class ActionStorage {
  static const String _actionsKey = 'recorded_actions';

  Future<void> saveActions(List<Map<String, dynamic>> actions) async {
    final prefs = await SharedPreferences.getInstance();
    final encodedActions = actions.map((action) => jsonEncode(action)).toList();
    await prefs.setStringList(_actionsKey, encodedActions);
  }

  Future<List<Map<String, dynamic>>> loadActions() async {
    final prefs = await SharedPreferences.getInstance();
    final encodedActions = prefs.getStringList(_actionsKey) ?? [];
    return encodedActions.map((action) => Map<String, dynamic>.from(jsonDecode(action))).toList();
  }

  Future<void> clearActions() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_actionsKey);
  }
}