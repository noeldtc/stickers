import 'dart:io';
import 'dart:typed_data';

/// WhatsApp only reports "handleStickerPackPreviewResult/failed" when it rejects a pack.
/// This checks the documented limits up front so the app can name the offending sticker.
const int maxAnimatedBytes = 500 * 1024;
const int maxStaticBytes = 100 * 1024;
const int maxAnimationMs = 10000;
const int minFrameMs = 8;

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
