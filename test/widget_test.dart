import 'package:downloader/presentation/widgets/error_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('ErrorCard shows Persian message, solution and retry', (tester) async {
    var retried = false;
    await tester.pumpWidget(
      MaterialApp(
        home: Directionality(
          textDirection: TextDirection.rtl,
          child: Scaffold(
            body: ErrorCard(code: 'DRM', detail: 'ERROR: This video is DRM protected', onRetry: () => retried = true),
          ),
        ),
      ),
    );
    expect(find.textContaining('DRM'), findsWidgets);
    expect(find.textContaining('راه‌حل'), findsOneWidget);
    expect(find.text('جزئیات فنی'), findsOneWidget);
    await tester.tap(find.text('تلاش دوباره'));
    expect(retried, isTrue);
  });
}
