import 'dart:io';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';
import 'package:stickers/src/constants.dart';

/// WhatsApp rejects animated stickers longer than this.
const int maxAnimationMs = 10000;

const _channel = MethodChannel('de.loicezt.stickers/methods');

int _even(int v) => max(2, v - v % 2);

/// Decodes a multi-frame GIF / animated WebP and re-encodes it as an MP4 so that it can go
/// through the regular video pipeline (trim → overlay → animated WebP).
///
/// Frames are downscaled to at most 512 px and the animation is cut at [maxAnimationMs].
/// Transparent areas end up black, as H.264 has no alpha channel.
Future<String> convertAnimatedImageToVideo(String path) async {
  final bytes = await File(path).readAsBytes();

  var codec = await ui.instantiateImageCodec(bytes);
  final probe = (await codec.getNextFrame()).image;
  final width = probe.width;
  final height = probe.height;
  probe.dispose();
  codec.dispose();

  final scale = min(1.0, 512 / max(width, height));
  codec = await ui.instantiateImageCodec(
    bytes,
    targetWidth: _even((width * scale).round()),
    targetHeight: _even((height * scale).round()),
  );

  final stamp = DateTime.now().millisecondsSinceEpoch;
  final framesDir = Directory("$mediaCacheDir/frames_$stamp");
  await framesDir.create(recursive: true);
  final output = "$mediaCacheDir/converted_$stamp.mp4";

  try {
    final durations = <int>[];
    var total = 0;
    for (var i = 0; i < codec.frameCount && total < maxAnimationMs; i++) {
      final frame = await codec.getNextFrame();
      // Browsers treat very short GIF delays as 100 ms; do the same.
      var duration = frame.duration.inMilliseconds;
      if (duration <= 10) duration = 100;
      duration = min(duration, maxAnimationMs - total);

      final png = await frame.image.toByteData(format: ui.ImageByteFormat.png);
      frame.image.dispose();
      if (png == null) throw Exception("Couldn't encode frame $i");
      await File("${framesDir.path}/frame_${i.toString().padLeft(5, '0')}.png")
          .writeAsBytes(png.buffer.asUint8List(png.offsetInBytes, png.lengthInBytes));
      durations.add(duration);
      total += duration;
    }
    if (durations.length < 2) throw Exception("The image has no animation");

    await _channel.invokeMethod('framesToVideo', {
      'framesDir': framesDir.path,
      'outputFile': output,
      'durationsMs': durations,
    });
    return output;
  } finally {
    codec.dispose();
    framesDir.delete(recursive: true).ignore();
  }
}
