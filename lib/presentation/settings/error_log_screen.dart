import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../actions.dart';
import '../providers.dart';

/// صفحهٔ «گزارش خطا»: لاگ محلی هر دو پروسه، با دکمهٔ کپی. هیچ چیزی جایی ارسال نمی‌شود.
class ErrorLogScreen extends ConsumerStatefulWidget {
  const ErrorLogScreen({super.key});

  @override
  ConsumerState<ErrorLogScreen> createState() => _ErrorLogScreenState();
}

class _ErrorLogScreenState extends ConsumerState<ErrorLogScreen> {
  late Future<String> _logs;

  @override
  void initState() {
    super.initState();
    _logs = ref.read(bridgeProvider).readLogs();
  }

  Future<void> _reload() async {
    setState(() => _logs = ref.read(bridgeProvider).readLogs());
  }

  Future<void> _deviceHeader(StringBuffer b) async {
    final d = await ref.read(deviceInfoProvider.future);
    b
      ..writeln(
        'device: ${d['model']} | sdk ${d['sdk']} | abis ${d['abis']} | ram ${d['totalRam']} | cores ${d['cores']}',
      )
      ..writeln('---');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('گزارش خطا'),
        actions: [
          IconButton(
            tooltip: 'کپی',
            icon: const Icon(Icons.copy),
            onPressed: () async {
              final text = await _logs;
              final b = StringBuffer();
              await _deviceHeader(b);
              b.write(text);
              await Clipboard.setData(ClipboardData(text: b.toString()));
              if (context.mounted) showMessage(context, 'گزارش خطا کپی شد.');
            },
          ),
          IconButton(
            tooltip: 'پاک کردن',
            icon: const Icon(Icons.delete_sweep_outlined),
            onPressed: () async {
              await ref.read(bridgeProvider).clearLogs();
              await _reload();
            },
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _reload,
        child: FutureBuilder<String>(
          future: _logs,
          builder: (c, snap) {
            if (snap.connectionState != ConnectionState.done) return const Center(child: CircularProgressIndicator());
            final text = snap.data ?? '';
            if (text.trim().isEmpty) {
              return ListView(
                children: const [
                  Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(child: Text('خطایی ثبت نشده است.')),
                  ),
                ],
              );
            }
            final lines = text.split('\n').reversed.toList();
            return ListView.separated(
              padding: const EdgeInsets.all(12),
              itemCount: lines.length,
              separatorBuilder: (_, _) => const Divider(height: 12),
              itemBuilder: (_, i) => SelectableText(
                lines[i],
                textDirection: TextDirection.ltr,
                style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
              ),
            );
          },
        ),
      ),
    );
  }
}
