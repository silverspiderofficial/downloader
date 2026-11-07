/// کارهای مشترک بین صفحه‌ها: افزودن به صف و نمایش پیام.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../domain/format_selection.dart';
import '../domain/models.dart';
import 'providers.dart';

void showMessage(BuildContext context, String text, {SnackBarAction? action}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(text), action: action, behavior: SnackBarBehavior.floating));
}

/// افزودن کیفیت انتخاب‌شده به صف: صدا با تصویر ادغام می‌شود.
Future<bool> enqueueFormat(WidgetRef ref, MediaInfo info, MediaFormat f) async {
  final s = ref.read(settingsProvider);
  final id = await ref.read(bridgeProvider).enqueue({
    'url': info.url,
    'title': info.title,
    'thumbnail': info.thumbnail ?? '',
    'formatSelector': buildSelector(f, s.container),
    'formatLabel': formatLabel(info, f, s.container),
    'mergeFormat': s.container.ytdlpValue,
    'embedSubs': s.embedSubs,
    'expectedBytes': estimatedSize(info, f, s.container) ?? 0,
  });
  return id != null;
}

/// افزودن سریع با کیفیت پیش‌فرض، بدون تحلیل (برای لینک‌های دریافتی از Share).
Future<bool> enqueueQuick(WidgetRef ref, String url) async {
  final s = ref.read(settingsProvider);
  final id = await ref.read(bridgeProvider).enqueue({
    'url': url,
    'title': '',
    'thumbnail': '',
    'formatSelector': quickSelector(s.defaultQuality),
    'formatLabel': quickLabel(s.defaultQuality),
    'mergeFormat': s.container.ytdlpValue,
    'embedSubs': s.embedSubs,
    'expectedBytes': 0,
  });
  return id != null;
}
