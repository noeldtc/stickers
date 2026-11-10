import 'dart:convert';
import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:stickers/src/pages/crop_page.dart';

class TweetMedia {
  final String path;
  final MediaType type;

  TweetMedia(this.path, this.type);
}

final _tweetUrl = RegExp(
  r'https?://(?:www\.|mobile\.)?(?:twitter|x|fxtwitter|vxtwitter|fixupx)\.com/[^/\s]+/status(?:es)?/(\d+)',
  caseSensitive: false,
);

/// Returns the tweet id if [text] contains a tweet link.
String? extractTweetId(String? text) {
  if (text == null) return null;
  return _tweetUrl.firstMatch(text)?.group(1);
}

/// Resolves the first media item of a tweet (via the public fxtwitter API)
/// and downloads it to the cache directory.
/// Throws [Exception] if the tweet has no media or a request fails.
Future<TweetMedia> downloadTweetMedia(String tweetId) async {
  final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
  try {
    final req = await client.getUrl(Uri.parse('https://api.fxtwitter.com/status/$tweetId'));
    req.headers.set(HttpHeaders.userAgentHeader, 'Mozilla/5.0 (Android) Stickers');
    final res = await req.close();
    if (res.statusCode != 200) throw Exception('Tweet lookup failed (${res.statusCode})');
    final json = jsonDecode(await res.transform(utf8.decoder).join()) as Map<String, dynamic>;
    final all = (json['tweet']?['media']?['all'] as List?) ?? const [];
    if (all.isEmpty) throw Exception('Tweet has no media');
    final item = all.first as Map<String, dynamic>;
    final url = item['url'] as String;
    final kind = item['type'] as String; // photo | video | gif
    final isVideo = kind == 'video' || kind == 'gif';

    final dir = await getTemporaryDirectory();
    var ext = Uri.parse(url).path.split('.').last;
    if (ext.isEmpty || ext.length > 4) ext = isVideo ? 'mp4' : 'jpg';
    final file = File('${dir.path}/tweet_${tweetId}_${DateTime.now().millisecondsSinceEpoch}.$ext');

    final dl = await client.getUrl(Uri.parse(url));
    final dres = await dl.close();
    if (dres.statusCode != 200) throw Exception('Download failed (${dres.statusCode})');
    await dres.pipe(file.openWrite());
    return TweetMedia(file.path, isVideo ? MediaType.video : MediaType.picture);
  } finally {
    client.close();
  }
}
