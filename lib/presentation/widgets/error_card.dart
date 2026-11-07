import 'package:flutter/material.dart';

import '../../core/error_messages.dart';

/// نمایش خطا به فارسی ساده همراه با راه‌حل و جزئیات فنی (قابل باز شدن).
class ErrorCard extends StatelessWidget {
  const ErrorCard({super.key, required this.code, this.detail, this.onRetry, this.onOpenSettings});

  final String code;
  final String? detail;
  final VoidCallback? onRetry;
  final VoidCallback? onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final m = errorMessage(code);
    final scheme = Theme.of(context).colorScheme;
    return Card(
      color: scheme.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.error_outline, color: scheme.onErrorContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    m.title,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(color: scheme.onErrorContainer),
                  ),
                ),
              ],
            ),
            if (m.solution.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text('راه‌حل: ${m.solution}', style: TextStyle(color: scheme.onErrorContainer)),
            ],
            if (detail != null && detail!.trim().isNotEmpty)
              Theme(
                data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
                child: ExpansionTile(
                  tilePadding: EdgeInsets.zero,
                  title: Text('جزئیات فنی', style: TextStyle(color: scheme.onErrorContainer, fontSize: 13)),
                  children: [
                    Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: SelectableText(
                        detail!,
                        textDirection: TextDirection.ltr,
                        style: TextStyle(fontSize: 12, color: scheme.onErrorContainer),
                      ),
                    ),
                  ],
                ),
              ),
            if (onRetry != null || onOpenSettings != null) ...[
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  if (onRetry != null)
                    FilledButton.tonalIcon(
                      onPressed: onRetry,
                      icon: const Icon(Icons.refresh),
                      label: const Text('تلاش دوباره'),
                    ),
                  if (onOpenSettings != null)
                    OutlinedButton.icon(
                      onPressed: onOpenSettings,
                      icon: const Icon(Icons.system_update_alt),
                      label: const Text('به‌روزرسانی موتور'),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}
