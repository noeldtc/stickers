import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:stickers/generated/intl/app_localizations.dart';

class ErrorDialog extends StatelessWidget {
  final String message;
  final String title;

  /// Optional technical details (e.g. a stack trace). Shown selectable and copied with the message.
  final String? details;

  const ErrorDialog({super.key, required this.message, required this.title, this.details});

  String get _fullText => details == null || details!.isEmpty ? message : '$message\n\n$details';

  @override
  Widget build(BuildContext context) {
    return Theme(
        data: Theme.of(context).copyWith(
            colorScheme: ColorScheme.fromSeed(seedColor: Colors.red, brightness: Theme.of(context).brightness),
            brightness: Theme.of(context).brightness),
        child: AlertDialog(
          title: Text(title),
          content: SingleChildScrollView(child: SelectableText(_fullText)),
          actions: [
            TextButton(
              onPressed: () => Clipboard.setData(ClipboardData(text: '$title\n$_fullText')),
              child: Text(MaterialLocalizations.of(context).copyButtonLabel),
            ),
            ElevatedButton(onPressed: () => Navigator.of(context).pop(), child: Text(AppLocalizations.of(context)!.ok))
          ],
        ));
  }
}
