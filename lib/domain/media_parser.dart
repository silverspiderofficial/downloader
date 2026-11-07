/// تبدیل خروجی JSON ‏yt-dlp (‎-J) به MediaInfo. این تابع سنگین است و در Isolate اجرا می‌شود.
library;

import 'dart:convert';

import 'models.dart';

class MediaParseException implements Exception {
  MediaParseException(this.code);
  final String code;
}

int? _int(Object? v) => v is num ? v.toInt() : null;
double? _dbl(Object? v) => v is num ? v.toDouble() : null;
String? _str(Object? v) => v is String && v.isNotEmpty ? v : null;

const _skipExts = {'mhtml', 'json', 'jpg', 'png', 'webp'};

MediaFormat? _parseFormat(Map<String, dynamic> f, double? duration) {
  final id = _str(f['format_id']);
  if (id == null) return null;
  final ext = _str(f['ext']) ?? '';
  final note = _str(f['format_note']);
  if (_skipExts.contains(ext) || (note ?? '').toLowerCase().contains('storyboard')) return null;
  if (f['has_drm'] == true) return null;
  var size = _int(f['filesize']);
  var approx = false;
  if (size == null || size <= 0) {
    size = _int(f['filesize_approx']);
    approx = true;
  }
  final tbr = _dbl(f['tbr']);
  if ((size == null || size <= 0) && tbr != null && duration != null && duration > 0) {
    size = (tbr * 1000 / 8 * duration).round();
    approx = true;
  }
  return MediaFormat(
    id: id,
    ext: ext,
    width: _int(f['width']),
    height: _int(f['height']),
    fps: _dbl(f['fps']),
    vcodec: _str(f['vcodec']),
    acodec: _str(f['acodec']),
    dynamicRange: _str(f['dynamic_range']),
    sizeBytes: (size != null && size > 0) ? size : null,
    sizeIsApprox: approx,
    tbr: tbr,
    abr: _dbl(f['abr']),
    note: note,
    protocol: _str(f['protocol']),
  );
}

int _cmpVideo(MediaFormat a, MediaFormat b) {
  int c(num? x, num? y) => (y ?? -1).compareTo(x ?? -1);
  var r = c(a.height, b.height);
  if (r != 0) return r;
  r = c(a.fps, b.fps);
  if (r != 0) return r;
  r = (b.isHdr ? 1 : 0).compareTo(a.isHdr ? 1 : 0);
  if (r != 0) return r;
  r = c(a.tbr, b.tbr);
  if (r != 0) return r;
  return c(a.sizeBytes, b.sizeBytes);
}

int _cmpAudio(MediaFormat a, MediaFormat b) {
  final ab = a.abr ?? a.tbr ?? -1;
  final bb = b.abr ?? b.tbr ?? -1;
  return bb.compareTo(ab);
}

/// تحلیل متن JSON کامل. خطاها با کد قابل نمایش برمی‌گردند.
MediaInfo parseMediaJson(String jsonText, String requestedUrl) {
  final Object? decoded;
  try {
    decoded = jsonDecode(jsonText);
  } on FormatException {
    throw MediaParseException('UNKNOWN');
  }
  if (decoded is! Map<String, dynamic>) throw MediaParseException('UNKNOWN');
  final j = decoded;
  final type = _str(j['_type']);
  final title = _str(j['title']) ?? _str(j['id']) ?? requestedUrl;
  if (type == 'playlist' || type == 'multi_video') {
    final entries = j['entries'];
    return MediaInfo(
      url: requestedUrl,
      title: title,
      isPlaylist: true,
      playlistCount: entries is List ? entries.length : (_int(j['playlist_count']) ?? 0),
      thumbnail: _str(j['thumbnail']),
      uploader: _str(j['uploader']) ?? _str(j['channel']),
      extractor: _str(j['extractor_key']),
    );
  }
  final duration = _dbl(j['duration']);
  final rawFormats = j['formats'];
  final formats = <MediaFormat>[];
  var drmCount = 0;
  var total = 0;
  if (rawFormats is List) {
    for (final f in rawFormats) {
      if (f is! Map<String, dynamic>) continue;
      total++;
      if (f['has_drm'] == true) {
        drmCount++;
        continue;
      }
      final mf = _parseFormat(f, duration);
      if (mf != null) formats.add(mf);
    }
  } else if (_str(j['url']) != null) {
    // سایت‌هایی که فقط یک فرمت دارند
    final mf = _parseFormat({...j, 'format_id': _str(j['format_id']) ?? 'best'}, duration);
    if (mf != null) formats.add(mf);
    total = 1;
  }
  final video = formats.where((f) => f.hasVideo).toList()..sort(_cmpVideo);
  final audio = formats.where((f) => f.isAudioOnly).toList()..sort(_cmpAudio);

  final subs = <String>[];
  final s = j['subtitles'];
  if (s is Map) {
    for (final k in s.keys) {
      if (k is String && k != 'live_chat') subs.add(k);
    }
  }
  final thumbs = j['thumbnail'];
  return MediaInfo(
    url: _str(j['webpage_url']) ?? requestedUrl,
    id: _str(j['id']),
    title: title,
    thumbnail: _str(thumbs),
    duration: duration,
    uploader: _str(j['uploader']) ?? _str(j['channel']) ?? _str(j['uploader_id']),
    extractor: _str(j['extractor_key']) ?? _str(j['extractor']),
    videoFormats: video,
    audioFormats: audio,
    subtitleLanguages: subs,
    drmOnly: total > 0 && drmCount == total,
    liveStatus: _str(j['live_status']),
  );
}
