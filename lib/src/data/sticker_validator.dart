import 'dart:io';
import 'dart:typed_data';

/// WhatsApp only reports "handleStickerPackPreviewResult/failed" when it rejects a pack.
/// This checks the documented limits up front so the app can name the offending sticker.
const int maxAnimatedBytes = 500 * 1024;
const int maxStaticBytes = 100 * 1024;
const int maxAnimationMs = 10000;
const int minFrameMs = 8;

/// Drops trailing frames of an animated WebP so it lasts at most [maxMs].
/// Returns the new bytes, or null if the file is not animated or already short enough.
Uint8List? truncateAnimatedWebp(Uint8List b, {int maxMs = maxAnimationMs}) {
  if (b.length < 20 || String.fromCharCodes(b.sublist(0, 4)) != 'RIFF' || String.fromCharCodes(b.sublist(8, 12)) != 'WEBP') {
    return null;
  }
  final data = ByteData.sublistView(b);
  final before = <Uint8List>[], frames = <Uint8List>[], after = <Uint8List>[];
  final frameMs = <int>[];
  int pos = 12;
  while (pos + 8 <= b.length) {
    final tag = String.fromCharCodes(b.sublist(pos, pos + 4));
    final size = data.getUint32(pos + 4, Endian.little);
    final end = (pos + 8 + size + (size & 1)).clamp(0, b.length);
    final chunk = Uint8List.sublistView(b, pos, end);
    if (tag == 'ANMF' && pos + 8 + 16 <= b.length) {
      frames.add(chunk);
      frameMs.add(b[pos + 8 + 12] | b[pos + 8 + 13] << 8 | b[pos + 8 + 14] << 16);
    } else if (frames.isEmpty) {
      before.add(chunk);
    } else {
      after.add(chunk);
    }
    pos = end;
  }
  int total = 0, keep = 0;
  for (final d in frameMs) {
    if (keep > 0 && total + d > maxMs) break;
    total += d;
    keep++;
  }
  if (frames.isEmpty || keep == frames.length) return null;

  final parts = [...before, ...frames.take(keep), ...after];
  final bodyLen = 4 + parts.fold<int>(0, (s, c) => s + c.length);
  final out = BytesBuilder();
  out.add('RIFF'.codeUnits);
  out.add((ByteData(4)..setUint32(0, bodyLen, Endian.little)).buffer.asUint8List());
  out.add('WEBP'.codeUnits);
  for (final c in parts) {
    out.add(c);
  }
  return out.toBytes();
}

/// Returns a human-readable problem with the sticker file, or null if it looks valid.
String? validateStickerFile(String path, {required bool animated}) {
  final file = File(path);
  if (!file.existsSync()) return "file not found";
  final Uint8List b = file.readAsBytesSync();

  final limit = animated ? maxAnimatedBytes : maxStaticBytes;
  if (b.length > limit) return "size ${(b.length / 1024).round()} KB exceeds ${limit ~/ 1024} KB";

  if (b.length < 20 || String.fromCharCodes(b.sublist(0, 4)) != 'RIFF' || String.fromCharCodes(b.sublist(8, 12)) != 'WEBP') {
    return "not a WebP file";
  }

  final data = ByteData.sublistView(b);
  int pos = 12;
  int width = 0, height = 0, totalMs = 0, frames = 0, minFrame = 1 << 30;
  bool hasAnimFlag = false;
  while (pos + 8 <= b.length) {
    final tag = String.fromCharCodes(b.sublist(pos, pos + 4));
    final size = data.getUint32(pos + 4, Endian.little);
    final body = pos + 8;
    if (tag == 'VP8X' && body + 10 <= b.length) {
      hasAnimFlag = (b[body] & 0x02) != 0;
      width = 1 + (b[body + 4] | b[body + 5] << 8 | b[body + 6] << 16);
      height = 1 + (b[body + 7] | b[body + 8] << 8 | b[body + 9] << 16);
    } else if (tag == 'ANMF' && body + 16 <= b.length) {
      final d = b[body + 12] | b[body + 13] << 8 | b[body + 14] << 16;
      totalMs += d;
      frames++;
      if (d < minFrame) minFrame = d;
    }
    pos = body + size + (size & 1);
  }

  if (width != 512 || height != 512) return "dimensions ${width}x$height, expected 512x512";
  if (animated) {
    if (!hasAnimFlag || frames == 0) return "pack is animated but this sticker is not";
    if (totalMs > maxAnimationMs) return "duration $totalMs ms exceeds $maxAnimationMs ms";
    if (minFrame < minFrameMs) return "a frame lasts $minFrame ms, minimum is $minFrameMs ms";
  } else if (hasAnimFlag && frames > 0) {
    return "pack is static but this sticker is animated";
  }
  return null;
}
