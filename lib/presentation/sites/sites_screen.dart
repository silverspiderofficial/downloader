import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/error_messages.dart';
import '../../core/persian.dart';
import '../providers.dart';

/// تب «سایت‌های کمکی»: فهرست واقعی سایت‌هایی که موتور فعلی yt-dlp پشتیبانی می‌کند (yt-dlp --list-extractors).
class SitesScreen extends ConsumerStatefulWidget {
  const SitesScreen({super.key});

  @override
  ConsumerState<SitesScreen> createState() => _SitesScreenState();
}

class _SitesScreenState extends ConsumerState<SitesScreen> {
  String _query = '';
  bool _requested = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final tab = ref.watch(tabProvider);
    // فهرست فقط وقتی کاربر این تب را باز کرد از موتور خوانده می‌شود
    if (tab == 3) _requested = true;
    final extractors = _requested ? ref.watch(extractorsProvider) : null;
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          sliver: SliverList.list(
            children: [
              Text('سایت‌های کمکی', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Text(
                'سایت‌هایی که موتور دانلود روی همین گوشی می‌شناسد. لینک صفحهٔ ویدیو از هر کدام را در تب «خانه» وارد کنید.',
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 12),
              TextField(
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: 'جستجوی سایت (مثلاً vimeo)',
                  isDense: true,
                ),
                textDirection: TextDirection.ltr,
                onChanged: (v) => setState(() => _query = v.trim().toLowerCase()),
              ),
            ],
          ),
        ),
        if (extractors != null)
          ...extractors.when(
            loading: () => [
              const SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      CircularProgressIndicator(),
                      SizedBox(height: 12),
                      Text('در حال خواندن فهرست از موتور…'),
                    ],
                  ),
                ),
              ),
            ],
            error: (e, _) => [
              SliverToBoxAdapter(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      Text(errorMessage('$e'.replaceFirst('Exception: ', '')).title),
                      const SizedBox(height: 8),
                      FilledButton.tonal(
                        onPressed: () => ref.invalidate(extractorsProvider),
                        child: const Text('تلاش دوباره'),
                      ),
                    ],
                  ),
                ),
              ),
            ],
            data: (all) {
              final list = _query.isEmpty ? all : all.where((s) => s.toLowerCase().contains(_query)).toList();
              return [
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                    child: Text('${fa(list.length)} مورد از ${fa(all.length)}', style: theme.textTheme.bodySmall),
                  ),
                ),
                SliverList.builder(
                  itemCount: list.length,
                  itemBuilder: (c, i) => ListTile(
                    dense: true,
                    leading: const Icon(Icons.public, size: 20),
                    title: Text(list[i], textDirection: TextDirection.ltr, textAlign: TextAlign.right),
                  ),
                ),
              ];
            },
          ),
      ],
    );
  }
}
