import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/persian.dart';
import '../../domain/models.dart';
import '../actions.dart';
import '../onboarding.dart';
import '../providers.dart';

enum _Sort { newest, name, size }

/// تب «کتابخانه»: فایل‌های پوشهٔ خروجی (Download/دانلودر یا پوشهٔ دلخواه).
class LibraryScreen extends ConsumerStatefulWidget {
  const LibraryScreen({super.key});

  @override
  ConsumerState<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends ConsumerState<LibraryScreen> {
  String _query = '';
  _Sort _sort = _Sort.newest;

  List<LibraryItem> _apply(List<LibraryItem> items) {
    final q = _query.trim().toLowerCase();
    final list = q.isEmpty ? [...items] : items.where((i) => i.name.toLowerCase().contains(q)).toList();
    switch (_sort) {
      case _Sort.newest:
        list.sort((a, b) => b.date.compareTo(a.date));
      case _Sort.name:
        list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
      case _Sort.size:
        list.sort((a, b) => b.size.compareTo(a.size));
    }
    return list;
  }

  Future<void> _delete(LibraryItem item) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('حذف فایل'),
        content: Text('«${item.name}» برای همیشه از حافظهٔ گوشی پاک شود؟'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('انصراف')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('حذف')),
        ],
      ),
    );
    if (ok != true) return;
    final done = await ref.read(bridgeProvider).deleteFile(item);
    if (!mounted) return;
    showMessage(context, done ? 'فایل حذف شد.' : 'حذف انجام نشد.');
    ref.invalidate(libraryProvider);
  }

  @override
  Widget build(BuildContext context) {
    final lib = ref.watch(libraryProvider);
    final perm = ref.watch(permissionStatusProvider).value;
    final needsPerm = perm != null && perm['storageNeeded'] == true && perm['storageGranted'] != true;
    final theme = Theme.of(context);
    return RefreshIndicator(
      onRefresh: () async {
        ref.invalidate(libraryProvider);
        await ref.read(libraryProvider.future);
      },
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
            sliver: SliverList.list(
              children: [
                Text('کتابخانه', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                TextField(
                  decoration: const InputDecoration(
                    prefixIcon: Icon(Icons.search),
                    hintText: 'جستجو در نام فایل‌ها',
                    isDense: true,
                  ),
                  onChanged: (v) => setState(() => _query = v),
                ),
                const SizedBox(height: 8),
                SegmentedButton<_Sort>(
                  segments: const [
                    ButtonSegment(value: _Sort.newest, label: Text('جدیدترین')),
                    ButtonSegment(value: _Sort.name, label: Text('نام')),
                    ButtonSegment(value: _Sort.size, label: Text('حجم')),
                  ],
                  selected: {_sort},
                  onSelectionChanged: (s) => setState(() => _sort = s.first),
                ),
                if (needsPerm) ...[
                  const SizedBox(height: 8),
                  Card(
                    child: ListTile(
                      leading: const Icon(Icons.lock_outline),
                      title: const Text('برای دیدن همهٔ فایل‌های پوشه، اجازهٔ حافظه لازم است.'),
                      trailing: TextButton(
                        onPressed: () async {
                          await requestStoragePermission(context, ref, perm);
                          ref.invalidate(permissionStatusProvider);
                          ref.invalidate(libraryProvider);
                        },
                        child: const Text('اجازه'),
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
          ...lib.when(
            loading: () => [
              const SliverFillRemaining(hasScrollBody: false, child: Center(child: CircularProgressIndicator())),
            ],
            error: (e, _) => [
              SliverToBoxAdapter(
                child: Padding(padding: const EdgeInsets.all(16), child: Text('خواندن پوشه ناموفق بود: $e')),
              ),
            ],
            data: (items) {
              final list = _apply(items);
              if (list.isEmpty) {
                return [
                  SliverFillRemaining(
                    hasScrollBody: false,
                    child: Center(
                      child: Text(
                        items.isEmpty ? 'هنوز فایلی در پوشهٔ دانلود نیست.' : 'نتیجه‌ای پیدا نشد.',
                        style: theme.textTheme.bodyMedium,
                      ),
                    ),
                  ),
                ];
              }
              return [
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Text(
                      '${fa(list.length)} فایل · ${formatBytes(list.fold<int>(0, (s, i) => s + i.size))}',
                      style: theme.textTheme.bodySmall,
                    ),
                  ),
                ),
                SliverList.builder(
                  itemCount: list.length,
                  itemBuilder: (c, i) {
                    final item = list[i];
                    return ListTile(
                      leading: CircleAvatar(child: Icon(item.isAudio ? Icons.music_note : Icons.movie_outlined)),
                      title: Text(item.name, maxLines: 2, overflow: TextOverflow.ellipsis),
                      subtitle: Text('${formatBytes(item.size)} · ${formatDate(item.date)}'),
                      onTap: () async {
                        final ok = await ref.read(bridgeProvider).openFile(item);
                        if (!ok && context.mounted) showMessage(context, 'برنامه‌ای برای باز کردن این فایل پیدا نشد.');
                      },
                      trailing: PopupMenuButton<String>(
                        onSelected: (v) {
                          if (v == 'share') ref.read(bridgeProvider).shareFile(item);
                          if (v == 'delete') _delete(item);
                        },
                        itemBuilder: (_) => const [
                          PopupMenuItem(value: 'share', child: Text('اشتراک‌گذاری')),
                          PopupMenuItem(value: 'delete', child: Text('حذف فایل')),
                        ],
                      ),
                    );
                  },
                ),
                const SliverToBoxAdapter(child: SizedBox(height: 24)),
              ];
            },
          ),
        ],
      ),
    );
  }
}
