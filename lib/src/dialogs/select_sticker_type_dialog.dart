import 'package:flutter/material.dart';
import 'package:stickers/generated/intl/app_localizations.dart';

enum StickerCreationChoice {
  animated,
  staticFrame,
}

/// Dialog asking the user whether they want an animated sticker or a static freeze frame
/// when choosing a video or animated GIF.
Future<StickerCreationChoice?> showStickerTypeChoiceDialog(
  BuildContext context, {
  required bool isVideo,
  required String currentPackTitle,
  required bool isCurrentPackAnimated,
}) {
  final theme = Theme.of(context);
  final l10n = AppLocalizations.of(context)!;

  return showDialog<StickerCreationChoice>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Row(
        children: [
          Icon(Icons.tune, color: theme.colorScheme.primary),
          const SizedBox(width: 10),
          const Expanded(child: Text("Configurar sticker")),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            isVideo
                ? "Has seleccionado un video. ¿Qué tipo de sticker deseas crear?"
                : "Has seleccionado una animación. ¿Qué tipo de sticker deseas crear?",
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: 16),
          Card(
            elevation: 0,
            color: theme.colorScheme.secondaryContainer.withAlpha(80),
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: () => Navigator.of(ctx).pop(StickerCreationChoice.animated),
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: Row(
                  children: [
                    CircleAvatar(
                      backgroundColor: theme.colorScheme.primaryContainer,
                      child: Icon(Icons.animation, color: theme.colorScheme.onPrimaryContainer),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            "Sticker animado",
                            style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            "Mantiene movimiento, recortable en duración (máx. 10 s y 500 KB)",
                            style: theme.textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 10),
          Card(
            elevation: 0,
            color: theme.colorScheme.surfaceContainerHighest.withAlpha(120),
            child: InkWell(
              borderRadius: BorderRadius.circular(12),
              onTap: () => Navigator.of(ctx).pop(StickerCreationChoice.staticFrame),
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: Row(
                  children: [
                    CircleAvatar(
                      backgroundColor: theme.colorScheme.tertiaryContainer,
                      child: Icon(Icons.camera_alt, color: theme.colorScheme.onTertiaryContainer),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            "Sticker estático (fotograma)",
                            style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            "Elige un fotograma exacto como foto fija (máx. 100 KB)",
                            style: theme.textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(null),
          child: Text(l10n.cancel),
        ),
      ],
    ),
  );
}
