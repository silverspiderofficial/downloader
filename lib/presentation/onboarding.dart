/// اولین اجرا: درخواست مجوزها با توضیح فارسی.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'providers.dart';

Future<bool> _explain(
  BuildContext context, {
  required IconData icon,
  required String title,
  required String body,
  required String allow,
}) async {
  final r = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (c) => AlertDialog(
      icon: Icon(icon),
      title: Text(title),
      content: Text(body),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('بعداً')),
        FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(allow)),
      ],
    ),
  );
  return r ?? false;
}

/// توضیح و درخواست اجازهٔ حافظه (فقط اندروید ۷ تا ۹ و ۱۳+ لازم است).
Future<void> requestStoragePermission(BuildContext context, WidgetRef ref, Map<Object?, Object?> status) async {
  if (status['storageNeeded'] != true || status['storageGranted'] == true) return;
  final legacy = status['storageRequired'] == true;
  final ok = await _explain(
    context,
    icon: Icons.sd_storage_outlined,
    title: 'اجازهٔ دسترسی به حافظه',
    body: legacy
        ? 'برای ذخیرهٔ ویدیوها در پوشهٔ «Download/دانلودر» گوشی، دانلودر به اجازهٔ نوشتن در حافظه نیاز دارد. '
              'فایل‌ها فقط روی گوشی خودتان ذخیره می‌شوند و هیچ چیزی جایی ارسال نمی‌شود.'
        : 'دانلودها بدون هیچ اجازه‌ای در پوشهٔ «Download/دانلودر» ذخیره می‌شوند. '
              'این اجازه فقط برای این است که کتابخانهٔ اپ ویدیوها و صداهای قبلی آن پوشه را (مثلاً بعد از نصب دوباره) هم نشان دهد.',
    allow: 'اجازه می‌دهم',
  );
  if (ok) await ref.read(bridgeProvider).requestStorage();
}

Future<void> runOnboarding(BuildContext context, WidgetRef ref) async {
  final bridge = ref.read(bridgeProvider);
  final status = await bridge.permissionStatus();
  if (!context.mounted) return;
  await requestStoragePermission(context, ref, status);
  if (!context.mounted) return;
  if (status['notificationsNeeded'] == true && status['notificationsGranted'] != true) {
    final ok = await _explain(
      context,
      icon: Icons.notifications_outlined,
      title: 'اعلان پیشرفت دانلود',
      body: 'دانلودها در پس‌زمینه ادامه پیدا می‌کنند. با اجازهٔ اعلان، درصد، سرعت و زمان باقی‌ماندهٔ هر دانلود و دکمه‌های توقف، ادامه و لغو را در نوار اعلان می‌بینید.',
      allow: 'اجازه می‌دهم',
    );
    if (ok) await bridge.requestNotifications();
  }
  if (!context.mounted) return;
  if (status['batteryExempt'] != true) {
    final ok = await _explain(
      context,
      icon: Icons.battery_saver_outlined,
      title: 'دانلود بدون قطعی در پس‌زمینه',
      body:
          'بعضی گوشی‌ها برای صرفه‌جویی در باتری، اپ‌ها را در پس‌زمینه می‌بندند و دانلود نیمه‌کاره می‌ماند. '
          'اگر دانلودر را از «بهینه‌سازی باتری» خارج کنید، دانلودهای طولانی و زمان‌بندی‌شده (مثلاً نیمه‌شب) بدون توقف انجام می‌شوند. '
          'دانلودر فقط وقتی کاری در صف هست فعال می‌ماند.',
      allow: 'خارج کردن از بهینه‌سازی',
    );
    if (ok) await bridge.requestBatteryExemption();
  }
  await ref.read(settingsProvider.notifier).update((s) => s.copyWith(onboardingDone: true));
  ref.invalidate(permissionStatusProvider);
}
