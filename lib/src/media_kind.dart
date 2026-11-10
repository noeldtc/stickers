import 'dart:io';
import 'dart:math';
import 'dart:typed_data';
import 'dart:ui' as ui;

/// What kind of sticker a media file turns into.
enum MediaKind {
  /// Still picture (JPEG, PNG, static WebP/GIF, HEIC, ...): static sticker.
  image,

  /// Multi-frame GIF or animated WebP: animated sticker (converted to video first).
  animatedImage,

  /// Any video container: animated sticker.
  video,

  /// Not something we can turn into a sticker.
  unknown,
}

const _imageExtensions = {'jpg', 'jpeg', 'png', 'webp', 'bmp', 'gif', 'heic', 'heif', 'avif', 'wbmp'};
const _videoExtensions = {'mp4', 'm4v', 'mov', 'webm', 'mkv', '3gp', '3gpp', 'avi', 'ts', 'mts'};

// ISO-BMFF brands used by still-image formats; any other `ftyp` brand is a video.
const _imageBrands = {'heic', 'heix', 'heim', 'heis', 'mif1', 'msf1', 'avif', 'avis'};

String _ascii(Uint8List b, int start, int end) => String.fromCharCodes(b.sublist(min(start, b.length), min(end, b.length)));

/// Sniffs the file content (falling back to the extension) to decide how it must be handled.
Future<MediaKind> detectMediaKind(String path) async {
  final Uint8List head;
  try {
    final raf = await File(path).open();
    try {
      head = await raf.read(64);
    } finally {
      await raf.close();
    }
  } on FileSystemException {
    return MediaKind.unknown;
  }

  final dot = path.lastIndexOf('.');
  final ext = dot == -1 ? '' : path.substring(dot + 1).toLowerCase();

  if (head.length >= 12) {
    if (_ascii(head, 0, 4) == 'GIF8') return _animatedIfMultiFrame(path);
    if (_ascii(head, 0, 4) == 'RIFF') {
      final form = _ascii(head, 8, 12);
      if (form == 'WEBP') {
        final animated = _ascii(head, 12, 16) == 'VP8X' && head.length > 20 && (head[20] & 0x02) != 0;
        return animated ? await _animatedIfMultiFrame(path) : MediaKind.image;
      }
      if (form == 'AVI ') return MediaKind.video;
    }
    if (head[0] == 0x89 && _ascii(head, 1, 4) == 'PNG') return MediaKind.image;
    if (head[0] == 0xFF && head[1] == 0xD8) return MediaKind.image;
    if (_ascii(head, 0, 2) == 'BM') return MediaKind.image;
    if (head[0] == 0x1A && head[1] == 0x45 && head[2] == 0xDF && head[3] == 0xA3) return MediaKind.video; // WebM/MKV
    if (_ascii(head, 4, 8) == 'ftyp') {
      return _imageBrands.contains(_ascii(head, 8, 12)) ? MediaKind.image : MediaKind.video;
    }
  }

  if (_videoExtensions.contains(ext)) return MediaKind.video;
  if (_imageExtensions.contains(ext)) return MediaKind.image;
  return MediaKind.unknown;
}

/// A GIF/WebP with a single frame is just a picture.
Future<MediaKind> _animatedIfMultiFrame(String path) async {
  try {
    final codec = await ui.instantiateImageCodec(await File(path).readAsBytes());
    final frames = codec.frameCount;
    codec.dispose();
    return frames > 1 ? MediaKind.animatedImage : MediaKind.image;
  } catch (_) {
    return MediaKind.image;
  }
}
