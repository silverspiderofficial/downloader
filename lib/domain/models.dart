/// مدل‌های لایهٔ domain (بدون وابستگی به Flutter).
library;

/// یک فرمت قابل دانلود از خروجی yt-dlp.
class MediaFormat {
  const MediaFormat({
    required this.id,
    required this.ext,
    this.width,
    this.height,
    this.fps,
    this.vcodec,
    this.acodec,
    this.dynamicRange,
    this.sizeBytes,
    this.sizeIsApprox = false,
    this.tbr,
    this.abr,
    this.note,
    this.protocol,
  });

  final String id;
  final String ext;
  final int? width;
  final int? height;
  final double? fps;
  final String? vcodec;
  final String? acodec;
  final String? dynamicRange;
  final int? sizeBytes;
  final bool sizeIsApprox;
  final double? tbr;
  final double? abr;
  final String? note;
  final String? protocol;

  /// کدک نامعلوم (هر دو null) در سایت‌های ساده یعنی فایل ویدیویی کامل.
  bool get hasVideo => vcodec != 'none' && (vcodec != null || height != null || width != null || acodec == null);
  bool get hasAudio => acodec != 'none' && (acodec != null || vcodec == null);
  bool get isAudioOnly => vcodec == 'none' && acodec != null && acodec != 'none';
  bool get isHdr => dynamicRange != null && dynamicRange != 'SDR';

  /// نام کوتاه کدک تصویر برای نمایش.
  String get videoCodecLabel {
    final c = (vcodec ?? '').toLowerCase();
    if (c.startsWith('avc') || c.startsWith('h264')) return 'H.264';
    if (c.startsWith('hev') || c.startsWith('hvc') || c.startsWith('h265')) return 'H.265';
    if (c.startsWith('av01') || c == 'av1') return 'AV1';
    if (c.startsWith('vp09') || c.startsWith('vp9')) return 'VP9';
    if (c.startsWith('vp8')) return 'VP8';
    return c.isEmpty ? ext.toUpperCase() : c.split('.').first.toUpperCase();
  }

  /// آیا کدک تصویر داخل MP4 جا می‌شود؟ (همان قاعدهٔ yt-dlp)
  bool get videoFitsMp4 {
    final c = (vcodec ?? '').toLowerCase();
    return c.startsWith('avc') ||
        c.startsWith('h264') ||
        c.startsWith('hev') ||
        c.startsWith('hvc') ||
        c.startsWith('av01') ||
        c == 'av1';
  }

  String get qualityLabel {
    final h = height;
    final b = StringBuffer();
    if (h != null) {
      b.write('${h}p');
      final f = fps;
      if (f != null && f > 30.5) b.write(f.round());
    } else if (note != null && note!.isNotEmpty) {
      b.write(note);
    } else {
      b.write(id);
    }
    if (isHdr) b.write(' HDR');
    return b.toString();
  }
}

class MediaInfo {
  const MediaInfo({
    required this.url,
    required this.title,
    this.id,
    this.thumbnail,
    this.duration,
    this.uploader,
    this.extractor,
    this.videoFormats = const [],
    this.audioFormats = const [],
    this.subtitleLanguages = const [],
    this.isPlaylist = false,
    this.playlistCount = 0,
    this.drmOnly = false,
    this.liveStatus,
  });

  final String url;
  final String title;
  final String? id;
  final String? thumbnail;
  final double? duration;
  final String? uploader;
  final String? extractor;

  /// فرمت‌های دارای تصویر، از بالاترین کیفیت به پایین.
  final List<MediaFormat> videoFormats;

  /// فرمت‌های فقط صدا، از بهترین به پایین.
  final List<MediaFormat> audioFormats;
  final List<String> subtitleLanguages;
  final bool isPlaylist;
  final int playlistCount;
  final bool drmOnly;
  final String? liveStatus;
}

enum JobState { queued, running, paused, waitingNetwork, completed, failed, canceled }

enum JobPhase { preparing, downloading, merging, embedding, publishing, done }

enum WaitReason { none, retryBackoff, scheduled, noNetwork, wifiRequired, lowResources }

T _enumFrom<T extends Enum>(List<T> values, String? raw, T fallback) {
  if (raw == null) return fallback;
  final key = raw.toLowerCase().replaceAll('_', '');
  for (final v in values) {
    if (v.name.toLowerCase() == key) return v;
  }
  return fallback;
}

/// وضعیت یک کار در صف، همان‌طور که پروسهٔ worker گزارش می‌دهد.
class DownloadJob {
  const DownloadJob({
    required this.id,
    required this.url,
    required this.title,
    required this.state,
    required this.phase,
    this.stage = 'DOWNLOAD',
    this.thumbnail = '',
    this.formatLabel = '',
    this.waitReason = WaitReason.none,
    this.progress = -1,
    this.downloadedBytes = 0,
    this.totalBytes = 0,
    this.speedBps = 0,
    this.etaSec = -1,
    this.attempts = 0,
    this.errorCode = '',
    this.errorDetail = '',
    this.notBefore = 0,
    this.createdAt = 0,
    this.finishedAt = 0,
    this.outputUri = '',
    this.outputName = '',
    this.sizeBytes = 0,
    this.expectedBytes = 0,
  });

  final int id;
  final String url;
  final String title;
  final JobState state;
  final JobPhase phase;
  final String stage;
  final String thumbnail;
  final String formatLabel;
  final WaitReason waitReason;
  final double progress;
  final int downloadedBytes;
  final int totalBytes;
  final int speedBps;
  final int etaSec;
  final int attempts;
  final String errorCode;
  final String errorDetail;
  final int notBefore;
  final int createdAt;
  final int finishedAt;
  final String outputUri;
  final String outputName;
  final int sizeBytes;
  final int expectedBytes;

  bool get isActive => state == JobState.queued || state == JobState.running || state == JobState.waitingNetwork;
  bool get isFinished => state == JobState.completed || state == JobState.failed || state == JobState.canceled;
  String get displayTitle => title.isNotEmpty ? title : url;

  static int _i(Object? v) => v is num ? v.toInt() : int.tryParse('$v') ?? 0;
  static double _d(Object? v) => v is num ? v.toDouble() : double.tryParse('$v') ?? -1;

  factory DownloadJob.fromJson(Map<String, dynamic> j) => DownloadJob(
    id: _i(j['id']),
    url: (j['url'] ?? '') as String,
    title: (j['title'] ?? '') as String,
    thumbnail: (j['thumbnail'] ?? '') as String,
    formatLabel: (j['formatLabel'] ?? '') as String,
    stage: (j['stage'] ?? 'DOWNLOAD') as String,
    state: _enumFrom(JobState.values, j['state'] as String?, JobState.queued),
    phase: _enumFrom(JobPhase.values, j['phase'] as String?, JobPhase.preparing),
    waitReason: _enumFrom(WaitReason.values, j['waitReason'] as String?, WaitReason.none),
    progress: _d(j['progress']),
    downloadedBytes: _i(j['downloadedBytes']),
    totalBytes: _i(j['totalBytes']),
    speedBps: _i(j['speedBps']),
    etaSec: j['etaSec'] == null ? -1 : _i(j['etaSec']),
    attempts: _i(j['attempts']),
    errorCode: (j['errorCode'] ?? '') as String,
    errorDetail: (j['errorDetail'] ?? '') as String,
    notBefore: _i(j['notBefore']),
    createdAt: _i(j['createdAt']),
    finishedAt: _i(j['finishedAt']),
    outputUri: (j['outputUri'] ?? '') as String,
    outputName: (j['outputName'] ?? '') as String,
    sizeBytes: _i(j['sizeBytes']),
    expectedBytes: _i(j['expectedBytes']),
  );
}

/// یک فایل در کتابخانه (پوشهٔ خروجی).
class LibraryItem {
  const LibraryItem({
    required this.name,
    required this.uri,
    required this.size,
    required this.date,
    required this.mime,
  });
  final String name;
  final String uri;
  final int size;
  final int date;
  final String mime;

  bool get isVideo => mime.startsWith('video/');
  bool get isAudio => mime.startsWith('audio/');

  factory LibraryItem.fromMap(Map<Object?, Object?> m) => LibraryItem(
    name: (m['name'] ?? '') as String,
    uri: (m['uri'] ?? '') as String,
    size: (m['size'] as num?)?.toInt() ?? 0,
    date: (m['date'] as num?)?.toInt() ?? 0,
    mime: (m['mime'] ?? '') as String,
  );
}

class EngineInfo {
  const EngineInfo({
    this.version = '',
    this.backupVersion = '',
    this.lastCheck = 0,
    this.initError = '',
    this.busy = false,
  });
  final String version;
  final String backupVersion;
  final int lastCheck;
  final String initError;
  final bool busy;

  factory EngineInfo.fromMap(Map<Object?, Object?> m) => EngineInfo(
    version: (m['version'] ?? '') as String,
    backupVersion: (m['backupVersion'] ?? '') as String,
    lastCheck: (m['lastCheck'] as num?)?.toInt() ?? 0,
    initError: (m['initError'] ?? '') as String,
    busy: m['busy'] == true,
  );
}
