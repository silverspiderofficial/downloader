import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/error_messages.dart';
import '../../core/persian.dart';
import '../../core/url_utils.dart';
import '../../domain/format_selection.dart';
import '../../domain/models.dart';
import '../actions.dart';
import '../providers.dart';
import '../widgets/error_card.dart';

/// تب «خانه»: ورود لینک، تحلیل و انتخاب کیفیت.
class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _paste() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    final url = extractUrl(data?.text);
    if (!mounted) return;
    if (url == null) {
      showMessage(context, 'در کلیپ‌بورد لینکی پیدا نشد.');
      return;
    }
    _controller.text = url;
    await _analyze();
  }

  Future<void> _analyze() async {
    FocusScope.of(context).unfocus();
    await ref.read(analysisProvider.notifier).analyze(_controller.text);
  }

  Future<void> _quick() async {
    FocusScope.of(context).unfocus();
    final url = extractUrl(_controller.text);
    if (url == null) {
      showMessage(context, errorMessage('NO_URL').title);
      return;
    }
    final ok = await enqueueQuick(ref, url);
    if (!mounted) return;
    _addedMessage(ok);
  }

  void _addedMessage(bool ok) {
    showMessage(
      context,
      ok ? 'به صف دانلود اضافه شد.' : 'افزودن به صف ناموفق بود؛ دوباره امتحان کنید.',
      action: ok ? SnackBarAction(label: 'دیدن صف', onPressed: () => ref.read(tabProvider.notifier).select(1)) : null,
    );
  }

  Future<void> _download(MediaInfo info, MediaFormat f) async {
    final s = ref.read(settingsProvider);
    final size = estimatedSize(info, f, s.container);
    final (free, _) = await ref.read(bridgeProvider).storageInfo();
    if (!mounted) return;
    if (size != null && free > 0 && size * 2 > free) {
      // فایل اول در حافظهٔ داخلی اپ ساخته و بعد به پوشهٔ دانلود منتقل می‌شود؛ پس تا دو برابر حجم لازم است
      final go = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          icon: const Icon(Icons.warning_amber_rounded),
          title: const Text('فضای خالی کم است'),
          content: Text(
            'حجم تقریبی این فایل ${formatBytes(size)} است و فضای آزاد گوشی ${formatBytes(free)}. '
            'برای ساخت و انتقال فایل تا دو برابر حجم آن فضا لازم است و ممکن است دانلود ناموفق شود.',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('انصراف')),
            FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('با این حال دانلود کن')),
          ],
        ),
      );
      if (go != true || !mounted) return;
    }
    final ok = await enqueueFormat(ref, info, f);
    if (!mounted) return;
    _addedMessage(ok);
  }

  @override
  Widget build(BuildContext context) {
    final analysis = ref.watch(analysisProvider);
    final quality = ref.watch(settingsProvider.select((s) => s.defaultQuality));
    ref.listen(analysisProvider, (prev, next) {
      // لینک دریافتی از Share یا کلیپ‌بورد در کادر نمایش داده می‌شود
      if (next is AnalysisLoading && _controller.text != next.url) _controller.text = next.url;
    });
    final loading = analysis is AnalysisLoading;
    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          sliver: SliverList.list(
            children: [
              Text(
                'دانلودر همه‌کاره',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 4),
              Text(
                'لینک ویدیو از یوتیوب، اینستاگرام، X، تیک‌تاک، ویمئو و صدها سایت دیگر را وارد کنید. همهٔ پردازش روی خود گوشی انجام می‌شود.',
                style: Theme.of(context).textTheme.bodyMedium
                    ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _controller,
                keyboardType: TextInputType.url,
                textDirection: TextDirection.ltr,
                textInputAction: TextInputAction.go,
                onSubmitted: (_) => _analyze(),
                decoration: InputDecoration(
                  labelText: 'لینک ویدیو',
                  hintText: 'https://…',
                  prefixIcon: const Icon(Icons.link),
                  suffixIcon: IconButton(
                    tooltip: 'چسباندن',
                    icon: const Icon(Icons.content_paste),
                    onPressed: loading ? null : _paste,
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: loading ? null : _analyze,
                      icon: const Icon(Icons.search),
                      label: const Text('تحلیل و انتخاب کیفیت'),
                    ),
                  ),
                  if (quality != DefaultQuality.ask) ...[
                    const SizedBox(width: 8),
                    OutlinedButton.icon(
                      onPressed: loading ? null : _quick,
                      icon: const Icon(Icons.bolt),
                      label: Text(quickLabel(quality)),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 8),
              const _StorageLine(),
              const SizedBox(height: 16),
            ],
          ),
        ),
        ...switch (analysis) {
          AnalysisIdle() => [const SliverToBoxAdapter(child: SizedBox.shrink())],
          AnalysisLoading() => [
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Column(
                  children: [
                    CircularProgressIndicator(),
                    SizedBox(height: 16),
                    Text('در حال تحلیل لینک با موتور yt-dlp…'),
                    SizedBox(height: 4),
                    Text('بار اول اجرای اپ چند ثانیه بیشتر طول می‌کشد.', style: TextStyle(fontSize: 12)),
                  ],
                ),
              ),
            ),
          ],
          AnalysisFailed(:final code, :final detail, :final url) => [
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              sliver: SliverToBoxAdapter(
                child: ErrorCard(
                  code: code,
                  detail: detail,
                  onRetry: code == 'NO_URL' ? null : () => ref.read(analysisProvider.notifier).analyze(url),
                  onOpenSettings: code == 'ENGINE_OUTDATED' || code == 'FORBIDDEN'
                      ? () => ref.read(tabProvider.notifier).select(4)
                      : null,
                ),
              ),
            ),
          ],
          AnalysisReady(:final info, :final selected) => _readySlivers(context, info, selected),
        },
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
      ],
    );
  }

  List<Widget> _readySlivers(BuildContext context, MediaInfo info, MediaFormat? selected) {
    final container = ref.watch(settingsProvider.select((s) => s.container));
    final embedSubs = ref.watch(settingsProvider.select((s) => s.embedSubs));
    final theme = Theme.of(context);
    return [
      SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        sliver: SliverToBoxAdapter(
          child: _InfoCard(info: info, embedSubs: embedSubs),
        ),
      ),
      SliverPadding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
        sliver: SliverToBoxAdapter(
          child: Text('کیفیت‌های موجود (${fa(info.videoFormats.length)})', style: theme.textTheme.titleMedium),
        ),
      ),
      SliverPadding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        sliver: SliverList.builder(
          itemCount: info.videoFormats.length,
          itemBuilder: (c, i) {
            final f = info.videoFormats[i];
            final size = estimatedSize(info, f, container);
            final isSel = selected?.id == f.id;
            return ListTile(
              selected: isSel,
              selectedTileColor: theme.colorScheme.primaryContainer.withValues(alpha: 0.5),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              leading: Icon(isSel ? Icons.radio_button_checked : Icons.radio_button_unchecked),
              title: Text(fa(f.qualityLabel), textDirection: TextDirection.ltr, textAlign: TextAlign.right),
              subtitle: Text(
                '${f.videoCodecLabel} · ${expectedContainer(info, f, container)}'
                '${f.hasAudio ? '' : ' · ادغام با بهترین صدا'}',
              ),
              trailing: Text(
                size == null ? 'حجم نامعلوم' : '${f.sizeIsApprox ? '~' : ''}${formatBytes(size)}',
                style: theme.textTheme.bodySmall,
              ),
              onTap: () => ref.read(analysisProvider.notifier).select(f),
            );
          },
        ),
      ),
      SliverPadding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
        sliver: SliverToBoxAdapter(
          child: FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
            onPressed: selected == null ? null : () => _download(info, selected),
            icon: const Icon(Icons.download),
            label: Text(
              selected == null
                  ? 'یک کیفیت انتخاب کنید'
                  : 'دانلود ${fa(selected.qualityLabel)}'
                        '${estimatedSize(info, selected, container) == null ? '' : ' · ${formatBytes(estimatedSize(info, selected, container))}'}',
            ),
          ),
        ),
      ),
    ];
  }
}

class _StorageLine extends ConsumerWidget {
  const _StorageLine();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = ref.watch(storageProvider);
    final folder = ref.watch(settingsProvider.select((x) => x.customFolderLabel));
    return s.when(
      data: (v) => Text(
        'فضای آزاد: ${formatBytes(v.$1)} از ${formatBytes(v.$2)} · ذخیره در ${folder.isEmpty ? 'Download/دانلودر' : folder}',
        style: Theme.of(context).textTheme.bodySmall,
      ),
      loading: () => const SizedBox(height: 16),
      error: (_, _) => const SizedBox.shrink(),
    );
  }
}

class _InfoCard extends StatelessWidget {
  const _InfoCard({required this.info, required this.embedSubs});
  final MediaInfo info;
  final bool embedSubs;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final thumb = info.thumbnail;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (thumb != null)
            AspectRatio(
              aspectRatio: 16 / 9,
              child: Image.network(
                thumb,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => ColoredBox(
                  color: theme.colorScheme.surfaceContainerHighest,
                  child: const Icon(Icons.movie_outlined, size: 48),
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(info.title, style: theme.textTheme.titleMedium, maxLines: 3, overflow: TextOverflow.ellipsis),
                const SizedBox(height: 6),
                Wrap(
                  spacing: 12,
                  runSpacing: 4,
                  children: [
                    if (info.duration != null) _meta(Icons.schedule, formatDuration(info.duration)),
                    if (info.uploader != null) _meta(Icons.person_outline, info.uploader!),
                    _meta(Icons.public, info.extractor ?? hostOf(info.url)),
                    if (info.subtitleLanguages.isNotEmpty)
                      _meta(
                        Icons.subtitles_outlined,
                        embedSubs
                            ? 'زیرنویس سایت: ${fa(info.subtitleLanguages.length)} زبان (داخل فایل)'
                            : 'زیرنویس سایت: ${fa(info.subtitleLanguages.length)} زبان (جاسازی خاموش است)',
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _meta(IconData icon, String text) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, size: 16),
      const SizedBox(width: 4),
      Flexible(child: Text(text, overflow: TextOverflow.ellipsis)),
    ],
  );
}
