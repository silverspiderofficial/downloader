import 'package:downloader/core/error_messages.dart';
import 'package:downloader/core/persian.dart';
import 'package:downloader/core/url_utils.dart';
import 'package:downloader/presentation/downloads/downloads_screen.dart';
import 'package:downloader/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('persian', () {
    test('digits', () => expect(fa('2026 v1.5'), '۲۰۲۶ v۱.۵'));
    test('bytes', () {
      expect(formatBytes(0), '—');
      expect(formatBytes(512), '۵۱۲ بایت');
      expect(formatBytes(1536), '۱٫۵ کیلوبایت');
      expect(formatBytes(1024 * 1024 * 300), '۳۰۰ مگابایت');
    });
    test('duration', () {
      expect(formatDuration(65), '۱:۰۵');
      expect(formatDuration(3725), '۱:۰۲:۰۵');
      expect(formatDuration(-1), '—');
    });
    test('percent', () => expect(formatPercent(42.6), '۴۳٪'));
    test('jalali', () {
      expect(toJalali(2026, 11, 7), (1405, 8, 16));
      expect(toJalali(2024, 3, 20), (1403, 1, 1));
    });
  });

  group('url extraction', () {
    test('finds url inside shared text', () {
      expect(extractUrl('Watch this! https://youtu.be/abc123?si=x. Nice'), 'https://youtu.be/abc123?si=x');
      expect(extractUrl('لینک: https://www.instagram.com/reel/XYZ/، ببین'), 'https://www.instagram.com/reel/XYZ/');
    });
    test('rejects non-urls', () {
      expect(extractUrl('hello world'), isNull);
      expect(extractUrl(null), isNull);
      expect(extractUrl('http://localhost'), isNull);
    });
    test('host', () => expect(hostOf('https://www.youtube.com/watch?v=1'), 'youtube.com'));
  });

  group('error messages', () {
    test('DRM message is clear Persian with explanation', () {
      final m = errorMessage('DRM');
      expect(m.title, contains('DRM'));
      expect(m.solution, isNotEmpty);
    });
    test('engine outdated suggests updating the engine', () {
      expect(errorMessage('ENGINE_OUTDATED').solution, contains('به‌روزرسانی موتور'));
    });
    test('every worker error code has a message with a solution', () {
      const codes = [
        'DRM',
        'NETWORK',
        'RATE_LIMITED',
        'FORBIDDEN',
        'UNSUPPORTED_URL',
        'LOGIN_REQUIRED',
        'GEO_BLOCKED',
        'UNAVAILABLE',
        'NO_SPACE',
        'FORMAT_UNAVAILABLE',
        'ENGINE_OUTDATED',
        'SUBTITLES',
        'ARIA2C',
        'MERGE_FAILED',
        'UPCOMING_LIVE',
        'STALLED',
        'STORAGE_PERMISSION',
        'PUBLISH_FAILED',
        'WORKER_RESTARTED',
        'TIMEOUT',
      ];
      for (final c in codes) {
        expect(isKnownErrorCode(c), isTrue, reason: c);
        expect(errorMessage(c).solution, isNotEmpty, reason: c);
      }
      expect(errorMessage('SOMETHING_NEW').solution, isNotEmpty);
    });
  });

  group('job status text', () {
    test('running download shows percent, speed and eta', () {
      const j = DownloadJob(
        id: 1,
        url: 'u',
        title: 't',
        state: JobState.running,
        phase: JobPhase.downloading,
        progress: 50,
        speedBps: 2 * 1024 * 1024,
        etaSec: 80,
        downloadedBytes: 100,
        totalBytes: 200,
      );
      final t = jobStatusText(j);
      expect(t, contains('۵۰٪'));
      expect(t, contains('مگابایت/ث'));
      expect(t, contains('۱:۲۰ مانده'));
    });
    test('merging and waiting states', () {
      const m = DownloadJob(id: 1, url: 'u', title: 't', state: JobState.running, phase: JobPhase.merging);
      expect(jobStatusText(m), contains('ادغام'));
      const w = DownloadJob(
        id: 1,
        url: 'u',
        title: 't',
        state: JobState.waitingNetwork,
        phase: JobPhase.preparing,
        waitReason: WaitReason.wifiRequired,
      );
      expect(jobStatusText(w), contains('وای‌فای'));
      const f = DownloadJob(
        id: 1,
        url: 'u',
        title: 't',
        state: JobState.failed,
        phase: JobPhase.preparing,
        errorCode: 'DRM',
      );
      expect(jobStatusText(f), contains('DRM'));
    });
    test('mime from name', () {
      expect(mimeFromName('a.mkv'), 'video/x-matroska');
      expect(mimeFromName('a.MP4'), 'video/mp4');
    });
  });
}
