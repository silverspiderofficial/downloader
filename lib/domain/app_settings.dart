/// تنظیمات کاربر. بخشی از آن (صف، شبکه، موتور) به پروسهٔ worker فرستاده می‌شود.
library;

import 'dart:convert';

import 'format_selection.dart';

enum AppThemeMode { system, light, dark }

class AppSettings {
  const AppSettings({
    this.themeMode = AppThemeMode.system,
    this.defaultQuality = DefaultQuality.ask,
    this.container = MergeContainer.mp4OrMkv,
    this.embedSubs = true,
    this.useAria2c = true,
    this.maxConcurrent = 3,
    this.wifiOnly = false,
    this.speedLimitKBps = 0,
    this.scheduleEnabled = false,
    this.scheduleMinute = 0,
    this.autoEngineCheck = true,
    this.customFolderUri = '',
    this.customFolderLabel = '',
    this.clipboardDetection = true,
    this.onboardingDone = false,
  });

  final AppThemeMode themeMode;
  final DefaultQuality defaultQuality;
  final MergeContainer container;
  final bool embedSubs;
  final bool useAria2c;
  final int maxConcurrent;
  final bool wifiOnly;
  final int speedLimitKBps;
  final bool scheduleEnabled;
  final int scheduleMinute;
  final bool autoEngineCheck;
  final String customFolderUri;
  final String customFolderLabel;
  final bool clipboardDetection;
  final bool onboardingDone;

  static const speedLimitOptions = [0, 256, 512, 1024, 2048, 5120, 10240];

  AppSettings copyWith({
    AppThemeMode? themeMode,
    DefaultQuality? defaultQuality,
    MergeContainer? container,
    bool? embedSubs,
    bool? useAria2c,
    int? maxConcurrent,
    bool? wifiOnly,
    int? speedLimitKBps,
    bool? scheduleEnabled,
    int? scheduleMinute,
    bool? autoEngineCheck,
    String? customFolderUri,
    String? customFolderLabel,
    bool? clipboardDetection,
    bool? onboardingDone,
  }) => AppSettings(
    themeMode: themeMode ?? this.themeMode,
    defaultQuality: defaultQuality ?? this.defaultQuality,
    container: container ?? this.container,
    embedSubs: embedSubs ?? this.embedSubs,
    useAria2c: useAria2c ?? this.useAria2c,
    maxConcurrent: maxConcurrent ?? this.maxConcurrent,
    wifiOnly: wifiOnly ?? this.wifiOnly,
    speedLimitKBps: speedLimitKBps ?? this.speedLimitKBps,
    scheduleEnabled: scheduleEnabled ?? this.scheduleEnabled,
    scheduleMinute: scheduleMinute ?? this.scheduleMinute,
    autoEngineCheck: autoEngineCheck ?? this.autoEngineCheck,
    customFolderUri: customFolderUri ?? this.customFolderUri,
    customFolderLabel: customFolderLabel ?? this.customFolderLabel,
    clipboardDetection: clipboardDetection ?? this.clipboardDetection,
    onboardingDone: onboardingDone ?? this.onboardingDone,
  );

  Map<String, Object> toMap() => {
    'themeMode': themeMode.name,
    'defaultQuality': defaultQuality.name,
    'container': container.name,
    'embedSubs': embedSubs,
    'useAria2c': useAria2c,
    'maxConcurrent': maxConcurrent,
    'wifiOnly': wifiOnly,
    'speedLimitKBps': speedLimitKBps,
    'scheduleEnabled': scheduleEnabled,
    'scheduleMinute': scheduleMinute,
    'autoEngineCheck': autoEngineCheck,
    'customFolderUri': customFolderUri,
    'customFolderLabel': customFolderLabel,
    'clipboardDetection': clipboardDetection,
    'onboardingDone': onboardingDone,
  };

  static T _enum<T extends Enum>(List<T> values, Object? raw, T def) =>
      values.firstWhere((v) => v.name == raw, orElse: () => def);

  factory AppSettings.fromMap(Map<String, Object?> m) {
    const d = AppSettings();
    T pick<T>(String k, T def) {
      final v = m[k];
      return v is T ? v : def;
    }

    return AppSettings(
      themeMode: _enum(AppThemeMode.values, m['themeMode'], d.themeMode),
      defaultQuality: _enum(DefaultQuality.values, m['defaultQuality'], d.defaultQuality),
      container: _enum(MergeContainer.values, m['container'], d.container),
      embedSubs: pick('embedSubs', d.embedSubs),
      useAria2c: pick('useAria2c', d.useAria2c),
      maxConcurrent: pick('maxConcurrent', d.maxConcurrent).clamp(1, 10),
      wifiOnly: pick('wifiOnly', d.wifiOnly),
      speedLimitKBps: pick('speedLimitKBps', d.speedLimitKBps),
      scheduleEnabled: pick('scheduleEnabled', d.scheduleEnabled),
      scheduleMinute: pick('scheduleMinute', d.scheduleMinute).clamp(0, 1439),
      autoEngineCheck: pick('autoEngineCheck', d.autoEngineCheck),
      customFolderUri: pick('customFolderUri', d.customFolderUri),
      customFolderLabel: pick('customFolderLabel', d.customFolderLabel),
      clipboardDetection: pick('clipboardDetection', d.clipboardDetection),
      onboardingDone: pick('onboardingDone', d.onboardingDone),
    );
  }

  /// بخشی که پروسهٔ worker لازم دارد (همان کلیدهای QueueSettings در Kotlin).
  String toWorkerJson() => jsonEncode({
    'maxConcurrentDownloads': maxConcurrent,
    'wifiOnly': wifiOnly,
    'speedLimitKBps': speedLimitKBps,
    'useAria2c': useAria2c,
    'embedSubs': embedSubs,
    'mergeFormat': container.ytdlpValue,
    'scheduleEnabled': scheduleEnabled,
    'scheduleMinuteOfDay': scheduleMinute,
    'autoEngineCheck': autoEngineCheck,
    'customFolderUri': customFolderUri,
  });
}
