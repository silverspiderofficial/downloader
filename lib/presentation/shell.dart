import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/persian.dart';
import '../core/url_utils.dart';
import '../domain/format_selection.dart';
import 'actions.dart';
import 'downloads/downloads_screen.dart';
import 'home/home_screen.dart';
import 'library/library_screen.dart';
import 'onboarding.dart';
import 'providers.dart';
import 'settings/settings_screen.dart';
import 'sites/sites_screen.dart';

/// قاب اصلی با پنج تب پایین.
class AppShell extends ConsumerStatefulWidget {
  const AppShell({super.key});

  @override
  ConsumerState<AppShell> createState() => _AppShellState();
}

class _AppShellState extends ConsumerState<AppShell> with WidgetsBindingObserver {
  StreamSubscription<String>? _shareSub;
  String? _lastClipboard;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    final bridge = ref.read(bridgeProvider);
    _shareSub = bridge.shares.listen(_handleShared);
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final initial = await bridge.initialShare();
      if (!mounted) return;
      if (initial != null) {
        await _handleShared(initial);
      } else {
        unawaited(_checkClipboard());
      }
      if (!mounted) return;
      if (!ref.read(settingsProvider).onboardingDone) await runOnboarding(context, ref);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _shareSub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      // اندروید ۱۰+ فقط وقتی پنجره فوکوس دارد اجازهٔ خواندن کلیپ‌بورد می‌دهد
      Future<void>.delayed(const Duration(milliseconds: 600), _checkClipboard);
      unawaited(ref.read(bridgeProvider).requestSnapshot());
    }
  }

  Future<void> _handleShared(String text) async {
    final url = extractUrl(text);
    if (!mounted) return;
    if (url == null) {
      showMessage(context, 'در متن اشتراک‌گذاری‌شده لینکی پیدا نشد.');
      return;
    }
    _lastClipboard = url;
    final q = ref.read(settingsProvider).defaultQuality;
    if (q == DefaultQuality.ask) {
      ref.read(tabProvider.notifier).select(0);
      await ref.read(analysisProvider.notifier).analyze(url);
    } else {
      final ok = await enqueueQuick(ref, url);
      if (!mounted) return;
      ref.read(tabProvider.notifier).select(1);
      showMessage(context, ok ? 'با کیفیت پیش‌فرض (${quickLabel(q)}) به صف اضافه شد.' : 'افزودن به صف ناموفق بود.');
    }
  }

  Future<void> _checkClipboard() async {
    if (!mounted || !ref.read(settingsProvider).clipboardDetection) return;
    final ClipboardData? data;
    try {
      data = await Clipboard.getData(Clipboard.kTextPlain);
    } on PlatformException {
      return;
    }
    final url = extractUrl(data?.text);
    if (url == null || url == _lastClipboard || !mounted) return;
    _lastClipboard = url;
    final a = ref.read(analysisProvider);
    if (a is AnalysisLoading && a.url == url) return;
    if (a is AnalysisReady && a.info.url == url) return;
    showMessage(
      context,
      'لینک در کلیپ‌بورد: ${hostOf(url)}',
      action: SnackBarAction(
        label: 'تحلیل',
        onPressed: () {
          ref.read(tabProvider.notifier).select(0);
          ref.read(analysisProvider.notifier).analyze(url);
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final tab = ref.watch(tabProvider);
    final active = ref.watch(jobsProvider.select((a) => a.value?.where((j) => j.isActive).length ?? 0));
    return Scaffold(
      body: SafeArea(
        child: IndexedStack(
          index: tab,
          children: const [HomeScreen(), DownloadsScreen(), LibraryScreen(), SitesScreen(), SettingsScreen()],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: ref.read(tabProvider.notifier).select,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        destinations: [
          const NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'خانه'),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: active > 0,
              label: Text(active > 99 ? '+۹۹' : fa(active)),
              child: const Icon(Icons.download_outlined),
            ),
            selectedIcon: const Icon(Icons.download),
            label: 'دانلودها',
          ),
          const NavigationDestination(
            icon: Icon(Icons.video_library_outlined),
            selectedIcon: Icon(Icons.video_library),
            label: 'کتابخانه',
          ),
          const NavigationDestination(
            icon: Icon(Icons.public_outlined),
            selectedIcon: Icon(Icons.public),
            label: 'سایت‌های کمکی',
          ),
          const NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            selectedIcon: Icon(Icons.settings),
            label: 'تنظیمات',
          ),
        ],
      ),
    );
  }
}
