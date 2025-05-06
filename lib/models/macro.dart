// lib/models/macro.dart

class Macro {
  String id;
  String name;
  List<Map<String, dynamic>> actions;

  Macro({required this.id, required this.name, required this.actions});

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'actions': actions,
  };

  factory Macro.fromJson(Map<String, dynamic> json) {
    return Macro(
      id: json['id'],
      name: json['name'],
      actions: List<Map<String, dynamic>>.from(json['actions']),
    );
  }
}