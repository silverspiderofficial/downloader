import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/error_messages.dart';
import '../../core/persian.dart';
import '../../data/native_bridge.dart';
import '../../domain/app_settings.dart';
import '../../domain/format_selection.dart';
import '../actions.dart';
import '../onboarding.dart';
import '../providers.dart';
import 'error_log_screen.dart';

/// تب «تنظیمات».
class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(settingsProvider);
    final n = ref.read(settingsProvider.notifier);
    final theme = Theme.of(context);

    Widget section(String title) => Padding(
      padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
      child: Text(title, style: theme.textTheme.titleSmall?.copyWith(color: theme.colorScheme.primary)),
    );

    return ListView(
      padding: const EdgeInsets.only(bottom: 32),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 0),
          child: Text('تنظیمات', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
        ),
        section('ظاهر'),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: SegmentedButton<AppThemeMode>(
            segments: const [
              ButtonSegment(value: AppThemeMode.system, label: Text('خودکار'), icon: Icon(Icons.brightness_auto)),
              ButtonSegment(value: AppThemeMode.light, label: Text('روشن'), icon: Icon(Icons.light_mode)),
              ButtonSegment(value: AppThemeMode.dark, label: Text('تاریک'), icon: Icon(Icons.dark_mode)),
            ],
            selected: {s.themeMode},
            onSelectionChanged: (v) => n.update((x) => x.copyWith(themeMode: v.first)),
          ),
        ),
        section('دانلود'),
        ListTile(
          title: const Text('کیفیت پیش‌فرض'),
          subtitle: Text(switch (s.defaultQuality) {
            DefaultQuality.best => 'همیشه بالاترین کیفیت',
            DefaultQuality.max1080 => 'حداکثر 1080p',
            DefaultQuality.ask => 'همیشه بپرس',
          }),
          trailing: const Icon(Icons.chevron_left),
          onTap: () => _choose<DefaultQuality>(context, 'کیفیت پیش‌فرض', s.defaultQuality, const {
            DefaultQuality.best: 'همیشه بالاترین کیفیت',
            DefaultQuality.max1080: 'حداکثر 1080p',
            DefaultQuality.ask: 'همیشه بپرس',
          }, (v) => n.update((x) => x.copyWith(defaultQuality: v))),
        ),
        ListTile(
          title: const Text('قالب خروجی'),
          subtitle: Text(s.container == MergeContainer.mkv ? 'همیشه MKV' : 'MP4 (اگر کدک‌ها در MP4 جا نشوند، MKV)'),
          trailing: const Icon(Icons.chevron_left),
          onTap: () => _choose<MergeContainer>(context, 'قالب خروجی', s.container, const {
            MergeContainer.mp4OrMkv: 'MP4 (اگر کدک‌ها در MP4 جا نشوند، MKV)',
            MergeContainer.mkv: 'همیشه MKV',
          }, (v) => n.update((x) => x.copyWith(container: v))),
        ),
        SwitchListTile(
          title: const Text('جاسازی زیرنویس سایت داخل فایل'),
          subtitle: const Text('اگر ویدیو زیرنویس داشته باشد همراه فایل دانلود و داخل آن قرار می‌گیرد.'),
          value: s.embedSubs,
          onChanged: (v) => n.update((x) => x.copyWith(embedSubs: v)),
        ),
        SwitchListTile(
          title: const Text('دانلود چنداتصالی با aria2c'),
          subtitle: const Text('برای فایل‌های معمولی سریع‌تر است؛ اگر خطا داد خودکار دانلودر داخلی استفاده می‌شود.'),
          value: s.useAria2c,
          onChanged: (v) => n.update((x) => x.copyWith(useAria2c: v)),
        ),
        SwitchListTile(
          title: const Text('تشخیص خودکار لینک در کلیپ‌بورد'),
          value: s.clipboardDetection,
          onChanged: (v) => n.update((x) => x.copyWith(clipboardDetection: v)),
        ),
        section('صف و شبکه'),
        ListTile(
          title: Text('سقف دانلود هم‌زمان: ${fa(s.maxConcurrent)}'),
          subtitle: Slider(
            value: s.maxConcurrent.toDouble(),
            min: 1,
            max: 10,
            divisions: 9,
            label: fa(s.maxConcurrent),
            onChanged: (v) => n.update((x) => x.copyWith(maxConcurrent: v.round())),
          ),
        ),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 16),
          child: Text(
            'تعداد واقعی بر اساس رم آزاد، تعداد هسته و دمای باتری خودکار کم می‌شود تا گوشی کند یا داغ نشود.',
            style: TextStyle(fontSize: 12),
          ),
        ),
        SwitchListTile(
          title: const Text('فقط با وای‌فای'),
          subtitle: const Text('با اینترنت همراه دانلودها منتظر می‌مانند.'),
          value: s.wifiOnly,
          onChanged: (v) => n.update((x) => x.copyWith(wifiOnly: v)),
        ),
        ListTile(
          title: const Text('محدودیت سرعت هر دانلود'),
          subtitle: Text(_speedLabel(s.speedLimitKBps)),
          trailing: const Icon(Icons.chevron_left),
          onTap: () => _choose<int>(context, 'محدودیت سرعت هر دانلود', s.speedLimitKBps, {
            for (final v in AppSettings.speedLimitOptions) v: _speedLabel(v),
          }, (v) => n.update((x) => x.copyWith(speedLimitKBps: v))),
        ),
        SwitchListTile(
          title: const Text('زمان‌بندی دانلود'),
          subtitle: Text(
            s.scheduleEnabled
                ? 'دانلودهای تازه ساعت ${_time(s.scheduleMinute)} شروع می‌شوند.'
                : 'دانلودهای تازه بلافاصله شروع می‌شوند.',
          ),
          value: s.scheduleEnabled,
          onChanged: (v) => n.update((x) => x.copyWith(scheduleEnabled: v)),
        ),
        if (s.scheduleEnabled)
          ListTile(
            leading: const Icon(Icons.schedule),
            title: Text('ساعت شروع: ${_time(s.scheduleMinute)}'),
            onTap: () async {
              final t = await showTimePicker(
                context: context,
                initialTime: TimeOfDay(hour: s.scheduleMinute ~/ 60, minute: s.scheduleMinute % 60),
              );
              if (t != null) await n.update((x) => x.copyWith(scheduleMinute: t.hour * 60 + t.minute));
            },
          ),
        section('ذخیره‌سازی'),
        const _StorageTile(),
        ListTile(
          title: const Text('پوشهٔ ذخیره'),
          subtitle: Text(s.customFolderUri.isEmpty ? 'Download/دانلودر (پیش‌فرض)' : s.customFolderLabel),
          trailing: s.customFolderUri.isEmpty
              ? const Icon(Icons.folder_open)
              : IconButton(
                  tooltip: 'برگشت به پوشهٔ پیش‌فرض',
                  icon: const Icon(Icons.restore),
                  onPressed: () async {
                    await ref.read(bridgeProvider).releaseFolder(s.customFolderUri);
                    await n.update((x) => x.copyWith(customFolderUri: '', customFolderLabel: ''));
                  },
                ),
          onTap: () async {
            final r = await ref.read(bridgeProvider).pickFolder();
            if (r == null) return;
            await n.update((x) => x.copyWith(customFolderUri: r.$1, customFolderLabel: r.$2));
          },
        ),
        section('موتور دانلود'),
        const _EngineSection(),
        section('پایداری و مجوزها'),
        const _PermissionsSection(),
        ListTile(
          leading: const Icon(Icons.bug_report_outlined),
          title: const Text('گزارش خطا'),
          subtitle: const Text('لاگ خطاها فقط روی همین گوشی نگه داشته می‌شود.'),
          trailing: const Icon(Icons.chevron_left),
          onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const ErrorLogScreen())),
        ),
        section('درباره'),
        const ListTile(
          leading: Icon(Icons.info_outline),
          title: Text('دانلودر همه‌کاره · نسخهٔ مرحلهٔ ۱'),
          subtitle: Text(
            'متن‌باز با مجوز GPL-3.0. بدون تبلیغ، آمارگیری یا سرور واسطه. '
            'اپ فقط به سایتی که لینکش را می‌دهید و مخزن رسمی yt-dlp در گیت‌هاب (برای به‌روزرسانی موتور) وصل می‌شود.',
          ),
        ),
      ],
    );
  }

  static String _speedLabel(int kb) {
    if (kb <= 0) return 'بدون محدودیت';
    if (kb >= 1024) return '${fa((kb / 1024).toStringAsFixed(kb % 1024 == 0 ? 0 : 1))} مگابایت بر ثانیه';
    return '${fa(kb)} کیلوبایت بر ثانیه';
  }

  static String _time(int minute) =>
      fa('${(minute ~/ 60).toString().padLeft(2, '0')}:${(minute % 60).toString().padLeft(2, '0')}');

  static void _choose<T>(
    BuildContext context,
    String title,
    T current,
    Map<T, String> options,
    void Function(T) onSelect,
  ) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(title, style: Theme.of(c).textTheme.titleMedium),
            ),
            for (final e in options.entries)
              ListTile(
                leading: Icon(e.key == current ? Icons.radio_button_checked : Icons.radio_button_unchecked),
                title: Text(e.value),
                onTap: () {
                  Navigator.pop(c);
                  onSelect(e.key);
                },
              ),
          ],
        ),
      ),
    );
  }
}

class _StorageTile extends ConsumerWidget {
  const _StorageTile();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final st = ref.watch(storageProvider);
    return ListTile(
      leading: const Icon(Icons.sd_storage_outlined),
      title: const Text('فضای آزاد حافظه'),
      subtitle: st.when(
        data: (v) {
          final (free, total) = v;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('${formatBytes(free)} آزاد از ${formatBytes(total)}'),
              if (total > 0) ...[
                const SizedBox(height: 6),
                LinearProgressIndicator(value: 1 - free / total, borderRadius: BorderRadius.circular(4)),
              ],
            ],
          );
        },
        loading: () => const Text('…'),
        error: (_, _) => const Text('نامعلوم'),
      ),
    );
  }
}

class _EngineSection extends ConsumerStatefulWidget {
  const _EngineSection();

  @override
  ConsumerState<_EngineSection> createState() => _EngineSectionState();
}

class _EngineSectionState extends ConsumerState<_EngineSection> {
  bool _working = false;

  String _statusText(String status, String version, String detail) => switch (status) {
    'updated' => 'موتور به نسخهٔ ${fa(version)} به‌روز شد و هش SHA-256 آن تأیید شد.',
    'upToDate' => 'موتور به‌روز است (${fa(version)}).',
    'rolledBack' => detail.isNotEmpty ? detail : 'نسخهٔ قبلی موتور (${fa(version)}) برگردانده شد.',
    'busy' => 'اول دانلودهای در حال اجرا تمام یا متوقف شوند، بعد دوباره امتحان کنید.',
    'noBackup' => 'نسخهٔ قبلی‌ای برای بازگشت وجود ندارد.',
    _ => 'به‌روزرسانی ناموفق بود. ${isKnownErrorCode(detail) ? errorMessage(detail).title : detail}'.trim(),
  };

  Future<void> _run(Future<EngineActionResult> Function() action) async {
    setState(() => _working = true);
    try {
      final r = await action();
      if (!mounted) return;
      showMessage(context, _statusText(r.status, r.version, r.detail));
    } finally {
      if (mounted) setState(() => _working = false);
      ref.invalidate(engineInfoProvider);
    }
  }

  @override
  Widget build(BuildContext context) {
    final info = ref.watch(engineInfoProvider);
    final s = ref.watch(settingsProvider);
    final bridge = ref.read(bridgeProvider);
    return Column(
      children: [
        info.when(
          data: (e) => ListTile(
            leading: const Icon(Icons.memory),
            title: Text(e.version.isEmpty ? 'yt-dlp (در حال آماده‌سازی)' : 'yt-dlp نسخهٔ ${fa(e.version)}'),
            subtitle: Text(
              e.initError.isNotEmpty
                  ? 'خطا در آماده‌سازی موتور: ${e.initError}'
                  : [
                      'همراه FFmpeg و aria2c داخلی',
                      if (e.lastCheck > 0) 'آخرین بررسی: ${formatDate(e.lastCheck)}',
                      if (e.backupVersion.isNotEmpty) 'نسخهٔ قبلی ذخیره‌شده: ${fa(e.backupVersion)}',
                    ].join('\n'),
            ),
          ),
          loading: () => const ListTile(leading: Icon(Icons.memory), title: Text('در حال خواندن نسخهٔ موتور…')),
          error: (e, _) => ListTile(leading: const Icon(Icons.memory), title: Text('نسخهٔ موتور خوانده نشد: $e')),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: _working ? null : () => _run(bridge.updateEngine),
                  icon: _working
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.system_update_alt),
                  label: const Text('به‌روزرسانی موتور'),
                ),
              ),
              const SizedBox(width: 8),
              if (info.value?.backupVersion.isNotEmpty ?? false)
                OutlinedButton(
                  onPressed: _working ? null : () => _run(bridge.rollbackEngine),
                  child: const Text('بازگشت به نسخهٔ قبلی'),
                ),
            ],
          ),
        ),
        SwitchListTile(
          title: const Text('بررسی خودکار هفتگی'),
          subtitle: const Text('هفته‌ای یک بار (وقتی دانلودی در جریان نیست) نسخهٔ تازهٔ yt-dlp بررسی و نصب می‌شود.'),
          value: s.autoEngineCheck,
          onChanged: (v) => ref.read(settingsProvider.notifier).update((x) => x.copyWith(autoEngineCheck: v)),
        ),
      ],
    );
  }
}

class _PermissionsSection extends ConsumerWidget {
  const _PermissionsSection();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final st = ref.watch(permissionStatusProvider);
    final bridge = ref.read(bridgeProvider);
    return st.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => const SizedBox.shrink(),
      data: (p) {
        Widget row(String title, bool ok, String okText, String needText, Future<void> Function() fix) => ListTile(
          leading: Icon(
            ok ? Icons.check_circle : Icons.error_outline,
            color: ok ? Colors.green : Theme.of(context).colorScheme.error,
          ),
          title: Text(title),
          subtitle: Text(ok ? okText : needText),
          trailing: ok
              ? null
              : TextButton(
                  onPressed: () async {
                    await fix();
                    ref.invalidate(permissionStatusProvider);
                  },
                  child: const Text('درست کردن'),
                ),
        );
        return Column(
          children: [
            if (p['storageNeeded'] == true)
              row(
                'اجازهٔ حافظه',
                p['storageGranted'] == true,
                'داده شده',
                'داده نشده',
                () => requestStoragePermission(context, ref, p),
              ),
            if (p['notificationsNeeded'] == true)
              row(
                'اعلان‌ها',
                p['notificationsGranted'] == true,
                'پیشرفت دانلود در نوار اعلان دیده می‌شود',
                'بدون اعلان، پیشرفت دانلود در نوار اعلان دیده نمی‌شود',
                () async {
                  await bridge.requestNotifications();
                },
              ),
            row(
              'بهینه‌سازی باتری',
              p['batteryExempt'] == true,
              'دانلودر از بهینه‌سازی باتری خارج است',
              'ممکن است سیستم دانلودهای طولانی را در پس‌زمینه متوقف کند',
              () async {
                await bridge.requestBatteryExemption();
              },
            ),
          ],
        );
      },
    );
  }
}
