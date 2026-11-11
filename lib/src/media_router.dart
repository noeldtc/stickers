import 'package:flutter/material.dart';
import 'package:stickers/generated/intl/app_localizations.dart';
import 'package:stickers/src/data/load_store.dart';
import 'package:stickers/src/data/sticker_pack.dart';
import 'package:stickers/src/dialogs/error_dialog.dart';
import 'package:stickers/src/dialogs/select_sticker_type_dialog.dart';
import 'package:stickers/src/globals.dart';
import 'package:stickers/src/media_kind.dart';
import 'package:stickers/src/pages/crop_page.dart';
import 'package:stickers/src/video/frame_extractor.dart';
import 'package:stickers/src/video/frames_to_video.dart';

/// Turns any image, GIF or video into a sticker, whatever the pack it was picked for.
///
/// Still pictures need a static pack, videos and animated images need an animated pack. When the
/// media doesn't match [pack], the user is asked whether it may go to a pack of the right type
/// (an existing one with room left, or a new one).
///
/// Returns the pack the sticker was added to, or null if nothing was added.
Future<StickerPack?> addMediaToPack(BuildContext context, StickerPack pack, String path) async {
  final l10n = AppLocalizations.of(context)!;
  final kind = await detectMediaKind(path);
  if (!context.mounted) return null;

  if (kind == MediaKind.unknown) {
    await showDialog(
      context: context,
      builder: (_) => ErrorDialog(title: l10n.unrecognizedFormat, message: l10n.unsupportedMedia),
    );
    return null;
  }

  bool wantAnimated = kind != MediaKind.image;

  // If the media is animated/video and user is in a static pack (or wants to configure),
  // offer the choice to create an animated sticker in an animated pack OR extract a static frame
  if (kind != MediaKind.image && !pack.animated) {
    final choice = await showStickerTypeChoiceDialog(
      context,
      isVideo: kind == MediaKind.video,
      currentPackTitle: pack.title,
      isCurrentPackAnimated: pack.animated,
    );
    if (choice == null || !context.mounted) return null;
    if (choice == StickerCreationChoice.staticFrame) {
      wantAnimated = false;
    }
  }

  var target = pack;
  var created = false;
  if (pack.animated != wantAnimated) {
    final existing = packs.where((p) => p != pack && p.animated == wantAnimated && p.stickers.length < 30).firstOrNull;
    final name = existing?.title ?? settingsController.defaultTitle;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l10n.otherPackTypeNeeded),
        content: Text(wantAnimated ? l10n.needsAnimatedPack(name) : l10n.needsStaticPack(name)),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: Text(l10n.cancel)),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: Text(l10n.continue_)),
        ],
      ),
    );
    if (ok != true) return null;
    if (existing != null) {
      target = existing;
    } else {
      target = StickerPack(
        settingsController.defaultTitle,
        settingsController.defaultAuthor,
        "pack_${DateTime.now().millisecondsSinceEpoch}",
        [],
        "0",
        wantAnimated,
      );
      packs.add(target);
      savePacks(packs);
      created = true;
    }
  }

  var mediaPath = path;
  if (wantAnimated && kind == MediaKind.animatedImage) {
    if (!context.mounted) return null;
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => PopScope(
        canPop: false,
        child: AlertDialog(
          content: Row(
            children: [
              const CircularProgressIndicator(year2023: false),
              const SizedBox(width: 24),
              Expanded(child: Text(l10n.convertingMedia)),
            ],
          ),
        ),
      ),
    );
    try {
      mediaPath = await convertAnimatedImageToVideo(path);
    } catch (e) {
      debugPrint("Animated image conversion failed: $e");
      if (context.mounted) {
        Navigator.of(context, rootNavigator: true).pop();
        await showDialog(
          context: context,
          builder: (_) => ErrorDialog(title: l10n.conversionFailed, message: e.toString()),
        );
      }
      _discardIfEmpty(target, created);
      return null;
    }
    if (!context.mounted) return null;
    Navigator.of(context, rootNavigator: true).pop();
  } else if (!wantAnimated && kind == MediaKind.animatedImage) {
    try {
      mediaPath = await extractAnimatedImageFrame(path);
    } catch (e) {
      debugPrint("Frame extraction failed: $e");
      if (context.mounted) {
        await showDialog(
          context: context,
          builder: (_) => ErrorDialog(title: l10n.conversionFailed, message: e.toString()),
        );
      }
      _discardIfEmpty(target, created);
      return null;
    }
  }

  if (!context.mounted) return null;
  final before = target.stickers.length;
  await Navigator.of(context).pushNamed(
    wantAnimated ? "/crop_video" : "/crop",
    arguments: EditArguments(pack: target, index: before, mediaPath: mediaPath),
  );

  if (target.stickers.length > before) return target;
  _discardIfEmpty(target, created);
  return null;
}

/// A pack created just for this sticker is dropped again if the user backed out.
void _discardIfEmpty(StickerPack target, bool created) {
  if (created && target.stickers.isEmpty) {
    packs.remove(target);
    savePacks(packs);
  }
}
