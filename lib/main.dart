import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'data/native_bridge.dart';
import 'domain/app_settings.dart';
import 'presentation/providers.dart';
import 'presentation/shell.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final bridge = NativeBridge();
  // خطاهای رابط کاربری فقط در لاگ محلی گوشی ثبت می‌شوند
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    unawaited(bridge.logError('flutter', details.exceptionAsString()));
  };
  WidgetsBinding.instance.platformDispatcher.onError = (error, stack) {
    unawaited(bridge.logError('dart', '$error'));
    return true;
  };
  runApp(
    ProviderScope(
      overrides: [prefsProvider.overrideWithValue(prefs), bridgeProvider.overrideWithValue(bridge)],
      child: const DownloaderApp(),
    ),
  );
}

class DownloaderApp extends ConsumerWidget {
  const DownloaderApp({super.key});

  ThemeData _theme(Brightness b) {
    final scheme = ColorScheme.fromSeed(seedColor: const Color(0xFF00796B), brightness: b);
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      fontFamily: 'Vazirmatn',
      inputDecorationTheme: const InputDecorationTheme(border: OutlineInputBorder()),
      cardTheme: const CardThemeData(margin: EdgeInsets.zero),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mode = ref.watch(settingsProvider.select((s) => s.themeMode));
    return MaterialApp(
      title: 'دانلودر همه‌کاره',
      debugShowCheckedModeBanner: false,
      locale: const Locale('fa', 'IR'),
      supportedLocales: const [Locale('fa', 'IR'), Locale('en', 'US')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: _theme(Brightness.light),
      darkTheme: _theme(Brightness.dark),
      themeMode: switch (mode) {
        AppThemeMode.light => ThemeMode.light,
        AppThemeMode.dark => ThemeMode.dark,
        AppThemeMode.system => ThemeMode.system,
      },
      home: const AppShell(),
    );
  }
}
