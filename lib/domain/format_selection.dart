/// انتخاب فرمت و ساخت رشتهٔ انتخاب yt-dlp: صدا همیشه با تصویر ادغام می‌شود (هرگز جدا ذخیره نمی‌شود).
library;

import 'models.dart';

enum DefaultQuality {
  best('best'),
  max1080('max1080'),
  ask('ask');

  const DefaultQuality(this.key);

  /// کلید ذخیره‌سازی ثابت (مستقل از مبهم‌سازی)
  final String key;
}

enum MergeContainer {
  mp4OrMkv('mp4OrMkv'),
  mkv('mkv');

  const MergeContainer(this.key);
  final String key;
}

extension MergeContainerX on MergeContainer {
  String get ytdlpValue => this == MergeContainer.mkv ? 'mkv' : 'mp4/mkv';
}

/// بهترین صدای مناسب برای یک فرمت تصویری بدون صدا.
MediaFormat? audioFor(MediaInfo info, MediaFormat video, MergeContainer container) {
  if (info.audioFormats.isEmpty) return null;
  if (container == MergeContainer.mp4OrMkv && video.videoFitsMp4) {
    for (final a in info.audioFormats) {
      if ((a.acodec ?? '').toLowerCase().startsWith('mp4a')) return a;
    }
  }
  return info.audioFormats.first;
}

/// رشتهٔ ‎-f برای yt-dlp.
/// اگر فرمت خودش صدا دارد، همان. وگرنه «تصویر + بهترین صدا»؛ برای MP4 صدای AAC ترجیح داده می‌شود
/// تا خروجی MP4 بماند، و اگر کدک‌ها در MP4 جا نشوند yt-dlp خروجی را MKV می‌کند.
String buildSelector(MediaFormat video, MergeContainer container) {
  final id = video.id;
  if (video.hasAudio) return id;
  if (container == MergeContainer.mp4OrMkv && video.videoFitsMp4) {
    return '$id+bestaudio[acodec^=mp4a]/$id+bestaudio/$id';
  }
  return '$id+bestaudio/$id';
}

/// حجم تقریبی خروجی نهایی (تصویر + صدای ادغام‌شده).
int? estimatedSize(MediaInfo info, MediaFormat video, MergeContainer container) {
  final v = video.sizeBytes;
  if (v == null) return null;
  if (video.hasAudio) return v;
  final a = audioFor(info, video, container)?.sizeBytes ?? 0;
  return v + a;
}

/// قالب خروجی احتمالی برای نمایش.
String expectedContainer(MediaInfo info, MediaFormat video, MergeContainer container) {
  if (container == MergeContainer.mkv) return 'MKV';
  if (video.hasAudio) return video.ext == 'mp4' ? 'MP4' : 'MKV';
  final a = audioFor(info, video, container);
  final audioMp4 = a == null || (a.acodec ?? '').toLowerCase().startsWith('mp4a');
  return video.videoFitsMp4 && audioMp4 ? 'MP4' : 'MKV';
}

/// فرمت پیش‌فرض بر اساس تنظیم کاربر؛ برای «همیشه بپرس» چیزی انتخاب نمی‌شود.
MediaFormat? pickDefault(MediaInfo info, DefaultQuality q) {
  if (info.videoFormats.isEmpty) return null;
  switch (q) {
    case DefaultQuality.ask:
      return null;
    case DefaultQuality.best:
      return info.videoFormats.first;
    case DefaultQuality.max1080:
      for (final f in info.videoFormats) {
        if ((f.height ?? 0) <= 1080) return f;
      }
      return info.videoFormats.last;
  }
}

/// انتخاب سریع بدون تحلیل (برای دریافت از Share وقتی پیش‌فرض «بپرس» نیست).
String quickSelector(DefaultQuality q) => switch (q) {
  DefaultQuality.max1080 => 'bestvideo*[height<=1080]+bestaudio/best[height<=1080]/best',
  _ => 'bestvideo*+bestaudio/best',
};

String quickLabel(DefaultQuality q) => switch (q) {
  DefaultQuality.max1080 => 'حداکثر 1080p',
  _ => 'بالاترین کیفیت',
};

/// برچسب کامل یک فرمت برای فهرست کیفیت‌ها، مثل «1080p60 HDR · VP9 · MKV».
String formatLabel(MediaInfo info, MediaFormat f, MergeContainer container) =>
    '${f.qualityLabel} · ${f.videoCodecLabel} · ${expectedContainer(info, f, container)}';
