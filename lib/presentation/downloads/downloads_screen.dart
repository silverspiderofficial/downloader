import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/error_messages.dart';
import '../../core/persian.dart';
import '../../domain/models.dart';
import '../actions.dart';
import '../providers.dart';
import '../widgets/error_card.dart';

String mimeFromName(String name) {
  final ext = name.contains('.') ? name.split('.').last.toLowerCase() : '';
  return switch (ext) {
    'mp4' || 'm4v' => 'video/mp4',
    'mkv' => 'video/x-matroska',
    'webm' => 'video/webm',
    'mov' => 'video/quicktime',
    '3gp' => 'video/3gpp',
    'm4a' => 'audio/mp4',
    'mp3' => 'audio/mpeg',
    'opus' || 'ogg' => 'audio/ogg',
    'flac' => 'audio/flac',
    _ => 'video/*',
  };
}

String _clock(int millis) {
  final d = DateTime.fromMillisecondsSinceEpoch(millis);
  String two(int n) => n.toString().padLeft(2, '0');
  final today = DateTime.now();
  final sameDay = d.year == today.year && d.month == today.month && d.day == today.day;
  return fa('${sameDay ? '' : '${formatDate(millis).split(' ').first} '}${two(d.hour)}:${two(d.minute)}');
}

/// متن وضعیت هر کار به فارسی.
String jobStatusText(DownloadJob j) {
  switch (j.state) {
    case JobState.running:
      switch (j.phase) {
        case JobPhase.preparing:
          return 'در حال آماده‌سازی…';
        case JobPhase.merging:
          return 'در حال ادغام صدا و تصویر…';
        case JobPhase.embedding:
          return 'در حال جاسازی زیرنویس و اطلاعات…';
        case JobPhase.publishing:
          return 'انتقال به پوشهٔ دانلود ${formatPercent(j.progress)}';
        case JobPhase.downloading:
        case JobPhase.done:
          final parts = <String>[
            if (j.progress >= 0) formatPercent(j.progress),
            if (j.speedBps > 0) formatSpeed(j.speedBps),
            if (j.etaSec >= 0) '${formatDuration(j.etaSec)} مانده',
            if (j.totalBytes > 0) '${formatBytes(j.downloadedBytes)} از ${formatBytes(j.totalBytes)}',
          ];
          return parts.isEmpty ? 'در حال دانلود…' : parts.join(' · ');
      }
    case JobState.queued:
      return switch (j.waitReason) {
        WaitReason.retryBackoff =>
          'تلاش دوباره (${fa(j.attempts + 1)} از ۵) ساعت ${_clock(j.notBefore)} — ${errorMessage(j.errorCode).title}',
        WaitReason.scheduled => 'زمان‌بندی‌شده: شروع ${_clock(j.notBefore)}',
        WaitReason.lowResources => 'منتظر آزاد شدن حافظه یا خنک شدن گوشی',
        _ => j.stage == 'PUBLISH' ? 'در صف انتقال به پوشهٔ دانلود' : 'در صف',
      };
    case JobState.waitingNetwork:
      return j.waitReason == WaitReason.wifiRequired ? 'منتظر اتصال وای‌فای' : 'منتظر برگشت اینترنت';
    case JobState.paused:
      return 'متوقف شده${j.progress > 0 ? ' · ${formatPercent(j.progress)}' : ''}';
    case JobState.completed:
      return 'تمام شد · ${formatBytes(j.sizeBytes)} · ${formatDate(j.finishedAt)}';
    case JobState.failed:
      return 'ناموفق: ${errorMessage(j.errorCode).title}';
    case JobState.canceled:
      return 'لغو شد';
  }
}

/// تب «دانلودها»: صف کارها با پیشرفت و کنترل‌ها.
class DownloadsScreen extends ConsumerWidget {
  const DownloadsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final jobsAsync = ref.watch(jobsProvider);
    final connected = ref.watch(workerConnectedProvider).value ?? true;
    final jobs = jobsAsync.value ?? const <DownloadJob>[];
    final active = jobs.where((j) => !j.isFinished).toList()..sort((a, b) => a.createdAt.compareTo(b.createdAt));
    final finished = jobs.where((j) => j.isFinished).toList()..sort((a, b) => b.finishedAt.compareTo(a.finishedAt));
    final bridge = ref.read(bridgeProvider);
    final hasRunnable = active.any((j) => j.state != JobState.paused);
    final hasPaused = active.any((j) => j.state == JobState.paused);

    return CustomScrollView(
      slivers: [
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
          sliver: SliverToBoxAdapter(
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    'صف دانلود',
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
                  ),
                ),
                if (hasRunnable)
                  TextButton.icon(
                    onPressed: bridge.pauseAll,
                    icon: const Icon(Icons.pause),
                    label: const Text('توقف همه'),
                  ),
                if (hasPaused)
                  TextButton.icon(
                    onPressed: bridge.resumeAll,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('ادامهٔ همه'),
                  ),
              ],
            ),
          ),
        ),
        if (!connected)
          const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Card(
                child: ListTile(
                  leading: Icon(Icons.sync_problem),
                  title: Text('در حال اتصال دوباره به بخش پردازش…'),
                  subtitle: Text('کارهای نیمه‌تمام بعد از اتصال از همان نقطه ادامه پیدا می‌کنند.'),
                ),
              ),
            ),
          ),
        if (jobs.isEmpty)
          SliverFillRemaining(
            hasScrollBody: false,
            child: Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.download_done_outlined, size: 64, color: Theme.of(context).colorScheme.outline),
                    const SizedBox(height: 12),
                    const Text('هنوز دانلودی ندارید.', textAlign: TextAlign.center),
                    const SizedBox(height: 12),
                    FilledButton.tonal(
                      onPressed: () => ref.read(tabProvider.notifier).select(0),
                      child: const Text('افزودن لینک'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        if (active.isNotEmpty) _header(context, 'در جریان (${fa(active.length)})'),
        SliverList.builder(
          itemCount: active.length,
          itemBuilder: (c, i) => _JobTile(job: active[i]),
        ),
        if (finished.isNotEmpty) _header(context, 'تمام‌شده (${fa(finished.length)})'),
        SliverList.builder(
          itemCount: finished.length,
          itemBuilder: (c, i) => _JobTile(job: finished[i]),
        ),
        const SliverToBoxAdapter(child: SizedBox(height: 24)),
      ],
    );
  }

  Widget _header(BuildContext context, String text) => SliverPadding(
    padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
    sliver: SliverToBoxAdapter(child: Text(text, style: Theme.of(context).textTheme.titleSmall)),
  );
}

class _JobTile extends ConsumerWidget {
  const _JobTile({required this.job});
  final DownloadJob job;

  Future<void> _act(WidgetRef ref, String action) => ref.read(bridgeProvider).jobAction(job.id, action);

  Future<void> _open(BuildContext context, WidgetRef ref) async {
    final ok = await ref
        .read(bridgeProvider)
        .openFile(
          LibraryItem(
            name: job.outputName,
            uri: job.outputUri,
            size: job.sizeBytes,
            date: job.finishedAt,
            mime: mimeFromName(job.outputName),
          ),
        );
    if (!ok && context.mounted) showMessage(context, 'برنامه‌ای برای باز کردن این فایل پیدا نشد.');
  }

  void _showError(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (c) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: ErrorCard(
            code: job.errorCode,
            detail: job.errorDetail,
            onRetry: () {
              Navigator.pop(c);
              unawaited(_act(ref, 'retry'));
            },
            onOpenSettings: job.errorCode == 'ENGINE_OUTDATED' || job.errorCode == 'FORBIDDEN'
                ? () {
                    Navigator.pop(c);
                    ref.read(tabProvider.notifier).select(4);
                  }
                : null,
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final running = job.state == JobState.running;
    final indeterminate =
        running &&
        (job.progress < 0 ||
            job.phase == JobPhase.merging ||
            job.phase == JobPhase.embedding ||
            job.phase == JobPhase.preparing);
    final statusColor = switch (job.state) {
      JobState.failed => theme.colorScheme.error,
      JobState.completed => theme.colorScheme.primary,
      _ => theme.colorScheme.onSurfaceVariant,
    };
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: switch (job.state) {
          JobState.completed when job.outputUri.isNotEmpty => () => _open(context, ref),
          JobState.failed => () => _showError(context, ref),
          _ => null,
        },
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Thumb(url: job.thumbnail),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      job.displayTitle,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyLarge,
                    ),
                    if (job.formatLabel.isNotEmpty)
                      Text(
                        fa(job.formatLabel),
                        style: theme.textTheme.bodySmall,
                        textDirection: TextDirection.ltr,
                        textAlign: TextAlign.right,
                      ),
                    const SizedBox(height: 4),
                    Text(jobStatusText(job), style: theme.textTheme.bodySmall?.copyWith(color: statusColor)),
                    if (running || job.state == JobState.paused) ...[
                      const SizedBox(height: 6),
                      LinearProgressIndicator(
                        value: indeterminate ? null : (job.progress.clamp(0, 100) / 100),
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ],
                  ],
                ),
              ),
              _actions(context, ref),
            ],
          ),
        ),
      ),
    );
  }

  Widget _actions(BuildContext context, WidgetRef ref) {
    final buttons = <Widget>[];
    void add(IconData icon, String tip, VoidCallback onTap) =>
        buttons.add(IconButton(icon: Icon(icon), tooltip: tip, onPressed: onTap, visualDensity: VisualDensity.compact));
    switch (job.state) {
      case JobState.running:
        if (job.stage == 'DOWNLOAD') add(Icons.pause, 'توقف', () => _act(ref, 'pause'));
        add(Icons.close, 'لغو', () => _act(ref, 'cancel'));
      case JobState.queued:
      case JobState.waitingNetwork:
        add(Icons.pause, 'توقف', () => _act(ref, 'pause'));
        add(Icons.close, 'لغو', () => _act(ref, 'cancel'));
      case JobState.paused:
        add(Icons.play_arrow, 'ادامه', () => _act(ref, 'resume'));
        add(Icons.close, 'لغو', () => _act(ref, 'cancel'));
      case JobState.failed:
      case JobState.canceled:
        add(Icons.refresh, 'تلاش دوباره', () => _act(ref, 'retry'));
        add(Icons.delete_outline, 'حذف از فهرست', () => _act(ref, 'remove'));
      case JobState.completed:
        if (job.outputUri.isNotEmpty) add(Icons.play_circle_outline, 'باز کردن', () => _open(context, ref));
        add(Icons.delete_outline, 'حذف از فهرست (فایل می‌ماند)', () => _act(ref, 'remove'));
    }
    return Column(mainAxisSize: MainAxisSize.min, children: buttons);
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url});
  final String url;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      width: 72,
      height: 48,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: const Icon(Icons.movie_outlined),
    );
    if (url.isEmpty) return placeholder;
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Image.network(
        url,
        width: 72,
        height: 48,
        fit: BoxFit.cover,
        cacheWidth: 216,
        errorBuilder: (_, _, _) => placeholder,
      ),
    );
  }
}
