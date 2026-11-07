/// ابزار نمایش فارسی: اعداد فارسی، حجم، سرعت، مدت و تاریخ.
library;

const _fa = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];

/// تبدیل رقم‌های لاتین به فارسی و ممیز به «٫».
String fa(Object? value) {
  final s = value?.toString() ?? '';
  final b = StringBuffer();
  for (final r in s.runes) {
    if (r >= 0x30 && r <= 0x39) {
      b.write(_fa[r - 0x30]);
    } else {
      b.writeCharCode(r);
    }
  }
  return b.toString();
}

String _fixed(double v) {
  final t = v >= 100 ? v.round().toString() : v.toStringAsFixed(1);
  return t.endsWith('.0') ? t.substring(0, t.length - 2) : t;
}

/// حجم خوانا، مثل «۱٫۵ مگابایت».
String formatBytes(num? bytes) {
  if (bytes == null || bytes <= 0) return '—';
  const units = ['بایت', 'کیلوبایت', 'مگابایت', 'گیگابایت', 'ترابایت'];
  var v = bytes.toDouble();
  var i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  final txt = i == 0 ? v.round().toString() : _fixed(v);
  return '${fa(txt).replaceAll('.', '٫')} ${units[i]}';
}

String formatSpeed(num? bps) => (bps == null || bps <= 0) ? '' : '${formatBytes(bps)}/ث';

/// مدت به‌صورت «۱:۰۲:۰۳» یا «۴:۰۵».
String formatDuration(num? seconds) {
  if (seconds == null || seconds < 0) return '—';
  final s = seconds.round();
  final h = s ~/ 3600;
  final m = (s % 3600) ~/ 60;
  final sec = s % 60;
  String two(int n) => n.toString().padLeft(2, '0');
  return fa(h > 0 ? '$h:${two(m)}:${two(sec)}' : '$m:${two(sec)}');
}

String formatPercent(num? p) => (p == null || p < 0) ? '' : '${fa(p.clamp(0, 100).toStringAsFixed(0))}٪';

/// تاریخ میلادی به شمسی (الگوریتم استاندارد تبدیل) برای نمایش در کتابخانه.
(int, int, int) toJalali(int gy, int gm, int gd) {
  const gdm = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];
  final gy2 = gm > 2 ? gy + 1 : gy;
  var days = 355666 + (365 * gy) + ((gy2 + 3) ~/ 4) - ((gy2 + 99) ~/ 100) + ((gy2 + 399) ~/ 400) + gd + gdm[gm - 1];
  var jy = -1595 + (33 * (days ~/ 12053));
  days %= 12053;
  jy += 4 * (days ~/ 1461);
  days %= 1461;
  if (days > 365) {
    jy += (days - 1) ~/ 365;
    days = (days - 1) % 365;
  }
  final jm = days < 186 ? 1 + days ~/ 31 : 7 + (days - 186) ~/ 30;
  final jd = 1 + (days < 186 ? days % 31 : (days - 186) % 30);
  return (jy, jm, jd);
}

String formatDate(int millis) {
  if (millis <= 0) return '';
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  final (y, m, day) = toJalali(d.year, d.month, d.day);
  String two(int n) => n.toString().padLeft(2, '0');
  return fa('$y/${two(m)}/${two(day)} ${two(d.hour)}:${two(d.minute)}');
}
