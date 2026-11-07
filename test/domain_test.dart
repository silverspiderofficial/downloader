import 'dart:convert';
import 'dart:io';

import 'package:downloader/domain/app_settings.dart';
import 'package:downloader/domain/format_selection.dart';
import 'package:downloader/domain/media_parser.dart';
import 'package:downloader/domain/models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final sample = File('test/fixtures/youtube_sample.json').readAsStringSync();

  group('parseMediaJson', () {
    final info = parseMediaJson(sample, 'https://youtu.be/abc123XYZ00');

    test('reads title, thumbnail, duration and site', () {
      expect(info.title, 'نمونهٔ ویدیو | Sample Video');
      expect(info.thumbnail, startsWith('https://i.ytimg.com/'));
      expect(info.duration, 212);
      expect(info.extractor, 'Youtube');
      expect(info.url, 'https://www.youtube.com/watch?v=abc123XYZ00');
      expect(info.isPlaylist, isFalse);
    });

    test('lists video formats best-first and skips storyboards and DRM', () {
      final ids = info.videoFormats.map((f) => f.id).toList();
      expect(ids.first, '337'); // 2160p60 HDR
      expect(ids, isNot(contains('sb0')));
      expect(ids, isNot(contains('drm1')));
      expect(ids.indexOf('299'), lessThan(ids.indexOf('137'))); // 60fps قبل از 30fps
      expect(info.audioFormats.first.id, '251');
      expect(info.videoFormats.length, 7);
    });

    test('estimates size from bitrate when filesize is missing', () {
      final f299 = info.videoFormats.firstWhere((f) => f.id == '299');
      expect(f299.sizeIsApprox, isTrue);
      expect(f299.sizeBytes, (6000 * 1000 / 8 * 212).round());
    });

    test('reads site subtitles but not live chat', () {
      expect(info.subtitleLanguages, unorderedEquals(['en', 'fa']));
    });

    test('labels', () {
      final f337 = info.videoFormats.first;
      expect(f337.qualityLabel, '2160p60 HDR');
      expect(f337.videoCodecLabel, 'VP9');
      expect(info.videoFormats.firstWhere((f) => f.id == '137').videoCodecLabel, 'H.264');
    });

    test('playlist is detected', () {
      final p = parseMediaJson(
        jsonEncode({
          '_type': 'playlist',
          'title': 'list',
          'entries': [{}, {}, {}],
        }),
        'u',
      );
      expect(p.isPlaylist, isTrue);
      expect(p.playlistCount, 3);
    });

    test('all-DRM content is flagged', () {
      final d = parseMediaJson(
        jsonEncode({
          'title': 'x',
          'formats': [
            {'format_id': 'a', 'has_drm': true, 'vcodec': 'avc1', 'acodec': 'none'},
          ],
        }),
        'u',
      );
      expect(d.drmOnly, isTrue);
      expect(d.videoFormats, isEmpty);
    });

    test('single-url sites produce one format', () {
      final d = parseMediaJson(jsonEncode({'title': 'clip', 'url': 'https://x/y.mp4', 'ext': 'mp4'}), 'u');
      expect(d.videoFormats.single.hasAudio, isTrue);
    });

    test('invalid json gives an error code', () {
      expect(() => parseMediaJson('not json', 'u'), throwsA(isA<MediaParseException>()));
    });
  });

  group('format selection', () {
    final info = parseMediaJson(sample, 'u');
    MediaFormat byId(String id) => info.videoFormats.firstWhere((f) => f.id == id);

    test('H.264 video merges with AAC audio to stay MP4', () {
      expect(buildSelector(byId('137'), MergeContainer.mp4OrMkv), '137+bestaudio[acodec^=mp4a]/137+bestaudio/137');
      expect(expectedContainer(info, byId('137'), MergeContainer.mp4OrMkv), 'MP4');
      expect(estimatedSize(info, byId('137'), MergeContainer.mp4OrMkv), 116000000 + 3433000);
    });

    test('VP9 video becomes MKV with best audio', () {
      expect(buildSelector(byId('248'), MergeContainer.mp4OrMkv), '248+bestaudio/248');
      expect(expectedContainer(info, byId('248'), MergeContainer.mp4OrMkv), 'MKV');
      expect(estimatedSize(info, byId('248'), MergeContainer.mp4OrMkv), 70000000 + 3500000);
    });

    test('progressive format is used as-is', () {
      expect(buildSelector(byId('18'), MergeContainer.mp4OrMkv), '18');
      expect(estimatedSize(info, byId('18'), MergeContainer.mp4OrMkv), 13250000);
    });

    test('always-MKV setting', () {
      expect(buildSelector(byId('137'), MergeContainer.mkv), '137+bestaudio/137');
      expect(expectedContainer(info, byId('137'), MergeContainer.mkv), 'MKV');
      expect(MergeContainer.mkv.ytdlpValue, 'mkv');
      expect(MergeContainer.mp4OrMkv.ytdlpValue, 'mp4/mkv');
    });

    test('default quality rules', () {
      expect(pickDefault(info, DefaultQuality.best)!.id, '337');
      expect(pickDefault(info, DefaultQuality.max1080)!.height, 1080);
      expect(pickDefault(info, DefaultQuality.max1080)!.fps, 60);
      expect(pickDefault(info, DefaultQuality.ask), isNull);
      expect(quickSelector(DefaultQuality.max1080), contains('height<=1080'));
    });
  });

  group('DownloadJob', () {
    test('parses worker JSON', () {
      final j = DownloadJob.fromJson({
        'id': 5,
        'url': 'https://e.com',
        'title': '',
        'state': 'WAITING_NETWORK',
        'phase': 'DOWNLOADING',
        'waitReason': 'NO_NETWORK',
        'progress': 42.5,
        'speedBps': 1000,
        'etaSec': 30,
      });
      expect(j.state, JobState.waitingNetwork);
      expect(j.phase, JobPhase.downloading);
      expect(j.waitReason, WaitReason.noNetwork);
      expect(j.progress, 42.5);
      expect(j.displayTitle, 'https://e.com');
      expect(j.isActive, isTrue);
    });
  });

  group('AppSettings', () {
    test('round-trips and builds worker json', () {
      const s = AppSettings(
        maxConcurrent: 7,
        wifiOnly: true,
        container: MergeContainer.mkv,
        scheduleEnabled: true,
        scheduleMinute: 90,
      );
      final back = AppSettings.fromMap(jsonDecode(jsonEncode(s.toMap())) as Map<String, Object?>);
      expect(back.maxConcurrent, 7);
      expect(back.container, MergeContainer.mkv);
      final w = jsonDecode(s.toWorkerJson()) as Map<String, dynamic>;
      expect(w['maxConcurrentDownloads'], 7);
      expect(w['wifiOnly'], true);
      expect(w['mergeFormat'], 'mkv');
      expect(w['scheduleMinuteOfDay'], 90);
    });

    test('clamps invalid values', () {
      final s = AppSettings.fromMap({'maxConcurrent': 50, 'defaultQuality': 'nonsense'});
      expect(s.maxConcurrent, 10);
      expect(s.defaultQuality, DefaultQuality.ask);
    });
  });
}
