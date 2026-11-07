/// پل ارتباطی Flutter با کد Kotlin (MethodChannel و EventChannel).
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:flutter/services.dart';

import '../domain/media_parser.dart';
import '../domain/models.dart';

class AnalysisResult {
  AnalysisResult.ok(this.info) : errorCode = null, errorDetail = null;
  AnalysisResult.error(this.errorCode, [this.errorDetail]) : info = null;
  final MediaInfo? info;
  final String? errorCode;
  final String? errorDetail;
}

class EngineActionResult {
  EngineActionResult(this.status, {this.version = '', this.previous = '', this.detail = ''});
  final String status;
  final String version;
  final String previous;
  final String detail;
}

List<DownloadJob> _parseJobs(String json) {
  final list = jsonDecode(json);
  if (list is! List) return const [];
  return [
    for (final e in list)
      if (e is Map<String, dynamic>) DownloadJob.fromJson(e),
  ];
}

class NativeBridge {
  NativeBridge() {
    _events.receiveBroadcastStream().listen(_onEvent, onError: (_) {});
  }

  static const _method = MethodChannel('ali.downloader/native');
  static const _events = EventChannel('ali.downloader/events');

  final _jobs = StreamController<List<DownloadJob>>.broadcast();
  final _shares = StreamController<String>.broadcast();
  final _worker = StreamController<bool>.broadcast();
  List<DownloadJob> lastJobs = const [];

  Stream<List<DownloadJob>> get jobs => _jobs.stream;
  Stream<String> get shares => _shares.stream;
  Stream<bool> get workerConnection => _worker.stream;

  void _onEvent(Object? e) {
    if (e is! Map) return;
    switch (e['type']) {
      case 'jobs':
        final json = e['json'] as String? ?? '[]';
        lastJobs = _parseJobs(json);
        _jobs.add(lastJobs);
      case 'share':
        final t = e['text'] as String?;
        if (t != null) _shares.add(t);
      case 'worker':
        _worker.add(e['connected'] == true);
    }
  }

  Future<T?> _call<T>(String method, [Object? args]) => _method.invokeMethod<T>(method, args);

  Future<Map<Object?, Object?>> _map(String method, [Object? args]) async =>
      (await _call<Map<Object?, Object?>>(method, args)) ?? const {};

  Future<String?> initialShare() => _call<String>('getInitialShare');

  /// تحلیل لینک در پروسهٔ worker و تبدیل خروجی JSON در یک Isolate جدا (بدون کند کردن رابط کاربری).
  Future<AnalysisResult> analyze(String url) async {
    final r = await _map('analyze', {'url': url});
    if (r['ok'] != true) return AnalysisResult.error(r['code'] as String? ?? 'UNKNOWN', r['detail'] as String?);
    final path = r['path'] as String;
    try {
      final info = await Isolate.run(() {
        final f = File(path);
        final text = f.readAsStringSync();
        try {
          f.deleteSync();
        } on FileSystemException {
          // پاک نشدن فایل موقت مشکلی ایجاد نمی‌کند؛ worker فایل‌های قدیمی را پاک می‌کند
        }
        return parseMediaJson(text, url);
      });
      return AnalysisResult.ok(info);
    } on MediaParseException catch (e) {
      return AnalysisResult.error(e.code);
    }
  }

  Future<int?> enqueue(Map<String, Object?> spec) async {
    final r = await _map('enqueue', spec);
    if (r['ok'] != true) return null;
    return (r['id'] as num?)?.toInt();
  }

  Future<void> jobAction(int id, String action) => _call('jobAction', {'id': id, 'action': action});
  Future<void> pauseAll() => _call('jobAction', {'id': 0, 'action': 'pauseAll'});
  Future<void> resumeAll() => _call('jobAction', {'id': 0, 'action': 'resumeAll'});
  Future<void> requestSnapshot() => _call('requestSnapshot');
  Future<void> updateSettings(String json) => _call('updateSettings', {'json': json});

  Future<EngineInfo> engineInfo() async => EngineInfo.fromMap(await _map('engineInfo'));

  EngineActionResult _engineResult(Map<Object?, Object?> r) => EngineActionResult(
    (r['status'] as String?) ?? 'failed',
    version: (r['version'] as String?) ?? '',
    previous: (r['previous'] as String?) ?? '',
    detail: (r['detail'] as String?) ?? (r['message'] as String?) ?? '',
  );

  Future<EngineActionResult> updateEngine() async => _engineResult(await _map('engineUpdate'));
  Future<EngineActionResult> rollbackEngine() async => _engineResult(await _map('engineRollback'));

  /// فهرست سایت‌های پشتیبانی‌شدهٔ موتور (خروجی yt-dlp --list-extractors).
  Future<List<String>> listExtractors() async {
    final r = await _map('listExtractors');
    if (r['ok'] != true) throw Exception(r['code'] ?? 'UNKNOWN');
    final path = r['path'] as String;
    return Isolate.run(() {
      final lines = File(path).readAsLinesSync();
      final out = <String>{};
      for (final l in lines) {
        final t = l.trim();
        if (t.isEmpty || t.contains('(CURRENTLY BROKEN)')) continue;
        out.add(t);
      }
      final list = out.toList()..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
      return list;
    });
  }

  Future<(int free, int total)> storageInfo() async {
    final r = await _map('storageInfo');
    return (((r['free'] as num?) ?? -1).toInt(), ((r['total'] as num?) ?? -1).toInt());
  }

  Future<(String uri, String label)?> pickFolder() async {
    final r = await _call<Map<Object?, Object?>>('pickFolder');
    if (r == null) return null;
    return (r['uri'] as String, r['label'] as String);
  }

  Future<void> releaseFolder(String uri) => _call('releaseFolder', {'uri': uri});

  Future<List<LibraryItem>> library(String customTree) async {
    final r = await _call<List<Object?>>('library', {'customTree': customTree}) ?? const [];
    return [
      for (final e in r)
        if (e is Map) LibraryItem.fromMap(e),
    ];
  }

  Future<bool> openFile(LibraryItem item) async =>
      await _call<bool>('libraryOpen', {'uri': item.uri, 'mime': item.mime}) ?? false;
  Future<void> shareFile(LibraryItem item) => _call('libraryShare', {'uri': item.uri, 'mime': item.mime});
  Future<bool> deleteFile(LibraryItem item) async => await _call<bool>('libraryDelete', {'uri': item.uri}) ?? false;

  Future<Map<Object?, Object?>> permissionStatus() => _map('permissionStatus');
  Future<bool> requestStorage() async => await _call<bool>('requestStorage') ?? false;
  Future<bool> requestNotifications() async => await _call<bool>('requestNotifications') ?? false;
  Future<bool> requestBatteryExemption() async => await _call<bool>('requestBatteryExemption') ?? false;

  Future<String> readLogs() async => await _call<String>('readLogs') ?? '';
  Future<void> clearLogs() => _call('clearLogs');
  Future<void> logError(String tag, String message) => _call('logError', {'tag': tag, 'message': message});

  Future<Map<Object?, Object?>> deviceInfo() => _map('deviceInfo');
}
