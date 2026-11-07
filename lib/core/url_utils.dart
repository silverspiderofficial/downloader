/// پیدا کردن لینک داخل متن (کلیپ‌بورد یا متن اشتراک‌گذاری‌شده از اپ‌های دیگر).
library;

final _urlRe = RegExp(r'''https?://[^\s<>"'«»]+''', caseSensitive: false);

/// اولین لینک http/https داخل متن، بدون علامت‌های انتهایی جمله.
String? extractUrl(String? text) {
  if (text == null) return null;
  final m = _urlRe.firstMatch(text);
  if (m == null) return null;
  var url = m.group(0)!;
  while (url.isNotEmpty && '.,;:!?)]}،؛'.contains(url[url.length - 1])) {
    url = url.substring(0, url.length - 1);
  }
  final uri = Uri.tryParse(url);
  if (uri == null || uri.host.isEmpty || !uri.host.contains('.')) return null;
  return url;
}

/// دامنهٔ کوتاه برای نمایش، مثل «youtube.com».
String hostOf(String url) {
  final h = Uri.tryParse(url)?.host ?? '';
  return h.startsWith('www.') ? h.substring(4) : h;
}
