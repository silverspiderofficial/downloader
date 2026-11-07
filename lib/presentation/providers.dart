/// وضعیت برنامه با Riverpod.
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/url_utils.dart';
import '../data/native_bridge.dart';
import '../domain/app_settings.dart';
import '../domain/format_selection.dart';
import '../domain/models.dart';

/// در main با نمونهٔ بارگذاری‌شده جایگزین می‌شود.
final prefsProvider = Provider<SharedPreferences>((ref) => throw StateError('prefs not initialized'));

final bridgeProvider = Provider<NativeBridge>((ref) => NativeBridge());

// ---------------- تنظیمات ----------------

final settingsProvider = NotifierProvider<SettingsNotifier, AppSettings>(SettingsNotifier.new);

class SettingsNotifier extends Notifier<AppSettings> {
  static const _key = 'settings.v1';

  @override
  AppSettings build() {
    final raw = ref.read(prefsProvider).getString(_key);
    var s = const AppSettings();
    if (raw != null) {
      try {
        final m = jsonDecode(raw);
        if (m is Map<String, Object?>) s = AppSettings.fromMap(m);
      } on FormatException {
        s = const AppSettings();
      }
    }
    // تنظیمات صف همیشه به پروسهٔ worker فرستاده می‌شود
    unawaited(ref.read(bridgeProvider).updateSettings(s.toWorkerJson()));
    return s;
  }

  Future<void> update(AppSettings Function(AppSettings) change) async {
    final next = change(state);
    state = next;
    await ref.read(prefsProvider).setString(_key, jsonEncode(next.toMap()));
    await ref.read(bridgeProvider).updateSettings(next.toWorkerJson());
  }
}

// ---------------- صف دانلود ----------------

final jobsProvider = StreamProvider<List<DownloadJob>>((ref) async* {
  final bridge = ref.watch(bridgeProvider);
  yield bridge.lastJobs;
  unawaited(bridge.requestSnapshot());
  yield* bridge.jobs;
});

final workerConnectedProvider = StreamProvider<bool>((ref) => ref.watch(bridgeProvider).workerConnection);

// ---------------- تب‌ها ----------------

final tabProvider = NotifierProvider<TabNotifier, int>(TabNotifier.new);

class TabNotifier extends Notifier<int> {
  @override
  int build() => 0;
  void select(int i) => state = i;
}

// ---------------- تحلیل لینک ----------------

sealed class AnalysisState {
  const AnalysisState();
}

class AnalysisIdle extends AnalysisState {
  const AnalysisIdle();
}

class AnalysisLoading extends AnalysisState {
  const AnalysisLoading(this.url);
  final String url;
}

class AnalysisReady extends AnalysisState {
  const AnalysisReady(this.info, this.selected);
  final MediaInfo info;
  final MediaFormat? selected;
}

class AnalysisFailed extends AnalysisState {
  const AnalysisFailed(this.url, this.code, [this.detail]);
  final String url;
  final String code;
  final String? detail;
}

final analysisProvider = NotifierProvider<AnalysisController, AnalysisState>(AnalysisController.new);

class AnalysisController extends Notifier<AnalysisState> {
  int _token = 0;

  @override
  AnalysisState build() => const AnalysisIdle();

  Future<void> analyze(String input) async {
    final url = extractUrl(input);
    if (url == null) {
      state = AnalysisFailed(input, 'NO_URL');
      return;
    }
    final token = ++_token;
    state = AnalysisLoading(url);
    final AnalysisResult r;
    try {
      r = await ref.read(bridgeProvider).analyze(url);
    } catch (e) {
      if (token == _token) state = AnalysisFailed(url, 'UNKNOWN', '$e');
      return;
    }
    if (token != _token) return;
    final info = r.info;
    if (info == null) {
      state = AnalysisFailed(url, r.errorCode ?? 'UNKNOWN', r.errorDetail);
    } else if (info.isPlaylist) {
      state = AnalysisFailed(url, 'PLAYLIST');
    } else if (info.drmOnly) {
      state = AnalysisFailed(url, 'DRM');
    } else if (info.videoFormats.isEmpty) {
      state = AnalysisFailed(url, 'NO_FORMATS');
    } else {
      state = AnalysisReady(info, pickDefault(info, ref.read(settingsProvider).defaultQuality));
    }
  }

  void select(MediaFormat f) {
    final s = state;
    if (s is AnalysisReady) state = AnalysisReady(s.info, f);
  }

  void clear() {
    _token++;
    state = const AnalysisIdle();
  }
}

// ---------------- کتابخانه، موتور، فضای حافظه ----------------

final libraryProvider = FutureProvider.autoDispose<List<LibraryItem>>((ref) {
  final tree = ref.watch(settingsProvider.select((s) => s.customFolderUri));
  // با تمام شدن هر دانلود فهرست تازه می‌شود
  ref.watch(jobsProvider.select((a) => a.value?.where((j) => j.state == JobState.completed).length ?? 0));
  return ref.read(bridgeProvider).library(tree);
});

final engineInfoProvider = FutureProvider.autoDispose<EngineInfo>((ref) => ref.read(bridgeProvider).engineInfo());

final extractorsProvider = FutureProvider<List<String>>((ref) => ref.read(bridgeProvider).listExtractors());

final storageProvider = FutureProvider.autoDispose<(int, int)>((ref) => ref.read(bridgeProvider).storageInfo());

final deviceInfoProvider = FutureProvider<Map<Object?, Object?>>((ref) => ref.read(bridgeProvider).deviceInfo());

final permissionStatusProvider = FutureProvider.autoDispose<Map<Object?, Object?>>(
  (ref) => ref.read(bridgeProvider).permissionStatus(),
);
