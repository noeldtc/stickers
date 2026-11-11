import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/services.dart';
import 'package:stickers/src/constants.dart';
import 'package:stickers/src/media_kind.dart';

const _channel = MethodChannel('de.loicezt.stickers/methods');

/// Extracts a still frame from a video at the specified [position].
/// Returns the path to the saved PNG image.
Future<String> extractVideoFrame(String videoPath, Duration position) async {
  final outDir = Directory(mediaCacheDir);
  if (!outDir.existsSync()) {
    outDir.createSync(recursive: true);
  }
  final outPath = '$mediaCacheDir/frame_${DateTime.now().millisecondsSinceEpoch}.png';

  final result = await _channel.invokeMethod<String>('extractVideoFrame', {
    'inputFile': videoPath,
    'outputFile': outPath,
    'timeUs': position.inMicroseconds.toString(),
  });

  return result ?? outPath;
}

/// Extracts a single frame from an animated GIF or animated WebP.
/// Returns the path to the saved PNG image.
Future<String> extractAnimatedImageFrame(String path, {int frameIndex = 0}) async {
  final bytes = await File(path).readAsBytes();
  final codec = await ui.instantiateImageCodec(bytes);
  try {
    final targetIndex = frameIndex.clamp(0, codec.frameCount - 1);
    ui.FrameInfo? frame;
    for (var i = 0; i <= targetIndex; i++) {
      frame?.image.dispose();
      frame = await codec.getNextFrame();
    }
    if (frame == null) {
      throw Exception('Could not decode frame from animated image');
    }

    final byteData = await frame.image.toByteData(format: ui.ImageByteFormat.png);
    frame.image.dispose();
    if (byteData == null) {
      throw Exception('Could not encode frame to PNG');
    }

    final outDir = Directory(mediaCacheDir);
    if (!outDir.existsSync()) {
      outDir.createSync(recursive: true);
    }
    final outPath = '$mediaCacheDir/frame_${DateTime.now().millisecondsSinceEpoch}.png';
    final Uint8List pngBytes = byteData.buffer.asUint8List(byteData.offsetInBytes, byteData.lengthInBytes);
    await File(outPath).writeAsBytes(pngBytes);
    return outPath;
  } finally {
    codec.dispose();
  }
}

/// Extracts a static image from any media (video, GIF, animated WebP, or still picture).
Future<String> extractStaticFrame(
  String path, {
  Duration position = Duration.zero,
  int frameIndex = 0,
}) async {
  final kind = await detectMediaKind(path);
  switch (kind) {
    case MediaKind.video:
      return extractVideoFrame(path, position);
    case MediaKind.animatedImage:
      return extractAnimatedImageFrame(path, frameIndex: frameIndex);
    case MediaKind.image:
    case MediaKind.unknown:
      return path;
  }
}
