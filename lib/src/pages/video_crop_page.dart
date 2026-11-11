import 'dart:async';
import 'dart:io';

import 'package:extended_image/extended_image.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:stickers/generated/intl/app_localizations.dart';
import 'package:stickers/src/checker_painter.dart';
import 'package:stickers/src/constants.dart';
import 'package:stickers/src/data/load_store.dart';
import 'package:stickers/src/data/sticker_pack.dart';
import 'package:stickers/src/dialogs/error_dialog.dart';
import 'package:stickers/src/globals.dart';
import 'package:stickers/src/pages/crop_page.dart';
import 'package:stickers/src/pages/default_page.dart';
import 'package:stickers/src/video/common.dart';
import 'package:stickers/src/video/crop_scale.dart';
import 'package:stickers/src/video/frame_extractor.dart';
import 'package:video_player/video_player.dart';

class VideoCropPage extends StatefulWidget {
  final StickerPack pack;
  final int index;
  final String imagePath;
  final GlobalKey<ExtendedImageEditorState> editorKey = GlobalKey<ExtendedImageEditorState>();

  VideoCropPage({
    required this.pack,
    required this.index,
    required this.imagePath,
    super.key,
  });

  static const routeName = "/crop_video";

  @override
  State<VideoCropPage> createState() => _VideoCropPageState();
}

class _VideoCropPageState extends State<VideoCropPage> with TickerProviderStateMixin {
  late final AnimationController _maskColorController;
  final ImageEditorController _editorController = ImageEditorController();
  late final VideoPlayerController _controller;
  double _btnOpacity = 1;
  bool _ready = false;
  bool _exporting = false;
  RangeValues _range = const RangeValues(0, 1);
  Duration _seekTarget = Duration.zero;

  String _fitMode = 'fit'; // 'fit' (contain) or 'fill' (crop to 1:1)
  double _playbackSpeed = 1.0;
  final List<double> _speeds = const [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];

  @override
  void initState() {
    super.initState();
    _maskColorController = AnimationController(vsync: this);
    final tween = Tween<double>(begin: 0.0, end: 1.0);
    final anim = CurvedAnimation(
        parent: _maskColorController, curve: Curves.ease, reverseCurve: Curves.ease);
    anim.drive(tween);
    _maskColorController.addListener(_animationListener);
    _controller = VideoPlayerController.file(
      File(widget.imagePath),
      viewType: VideoViewType.platformView,
      videoPlayerOptions: VideoPlayerOptions(mixWithOthers: true),
    );
    _controller.initialize().then((_) {
      if (mounted) setState(() => _ready = true);
    }, onError: (Object e, StackTrace st) {
      if (!mounted) return;
      showDialog(
          context: context,
          builder: (context) {
            return ErrorDialog(
              title: AppLocalizations.of(context)!.couldntLoadVideo,
              message: e.toString(),
            );
          }).then((_) {
        if (mounted) Navigator.of(context).pop();
      });
    });
    _controller.addListener(_videoListener);
    _controller.setVolume(0);
  }

  void _videoListener() {
    if (_editing) return;
    setState(() {});
    if (_controller.value.position > _controller.value.duration * _range.end) {
      _requestSeek(_controller.value.duration * _range.start);
    }
  }

  void _animationListener() {
    setState(() {});
  }

  @override
  void dispose() {
    _maskColorController.removeListener(_animationListener);
    _maskColorController.dispose();
    _controller.dispose();
    service.dispose();
    super.dispose();
  }

  void _cycleSpeed() {
    final nextIndex = (_speeds.indexOf(_playbackSpeed) + 1) % _speeds.length;
    final nextSpeed = _speeds[nextIndex];
    setState(() {
      _playbackSpeed = nextSpeed;
    });
    _controller.setPlaybackSpeed(nextSpeed);
    HapticFeedback.lightImpact();
  }

  Future<void> _captureFrameAsStatic() async {
    if (!_ready || _exporting) return;
    _controller.pause();
    setState(() {});

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => const Center(child: CircularProgressIndicator()),
    );

    try {
      final framePath = await extractVideoFrame(
        widget.imagePath,
        _controller.value.position,
      );
      if (!mounted) return;
      Navigator.of(context, rootNavigator: true).pop(); // dismiss loading

      // Target pack must be a static pack
      var targetPack = widget.pack;
      if (widget.pack.animated) {
        final existing = packs.where((p) => !p.animated && p.stickers.length < 30).firstOrNull;
        if (existing != null) {
          targetPack = existing;
        } else {
          targetPack = StickerPack(
            settingsController.defaultTitle,
            settingsController.defaultAuthor,
            "pack_${DateTime.now().millisecondsSinceEpoch}",
            [],
            "0",
            false,
          );
          packs.add(targetPack);
          savePacks(packs);
        }
      }

      final index = targetPack.stickers.length;
      if (!mounted) return;
      Navigator.of(context).pushNamed(
        "/crop",
        arguments: EditArguments(
          pack: targetPack,
          index: index,
          mediaPath: framePath,
          type: MediaType.picture,
        ),
      );
    } catch (e) {
      if (mounted) {
        Navigator.of(context, rootNavigator: true).pop();
        showDialog(
          context: context,
          builder: (ctx) => ErrorDialog(
            title: "Error al capturar fotograma",
            message: e.toString(),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context)!;

    return DefaultActivity(
      appBar: AppBar(
        title: Text(l10n.trimVideo),
        actions: [
          IconButton(
            tooltip: "Capturar fotograma estático",
            icon: const Icon(Icons.camera_alt),
            onPressed: _ready && !_exporting ? _captureFrameAsStatic : null,
          ),
        ],
      ),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Center(
                child: Padding(
                  padding: const EdgeInsets.all(12.0),
                  child: AspectRatio(
                    aspectRatio: 1.0,
                    child: Container(
                      clipBehavior: Clip.antiAlias,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: _fitMode == 'fill'
                              ? theme.colorScheme.primary.withAlpha(150)
                              : theme.colorScheme.outlineVariant,
                          width: _fitMode == 'fill' ? 2 : 1,
                        ),
                      ),
                      child: Stack(
                        alignment: Alignment.center,
                        children: [
                          if (_fitMode == 'fit')
                            Positioned.fill(
                              child: CustomPaint(
                                painter: CheckerPainter(context),
                              ),
                            ),
                          if (_ready)
                            _fitMode == 'fill'
                                ? SizedBox.expand(
                                    child: FittedBox(
                                      fit: BoxFit.cover,
                                      clipBehavior: Clip.antiAlias,
                                      child: SizedBox(
                                        width: _controller.value.size.width,
                                        height: _controller.value.size.height,
                                        child: VideoPlayer(_controller),
                                      ),
                                    ),
                                  )
                                : AspectRatio(
                                    aspectRatio: _controller.value.aspectRatio,
                                    child: VideoPlayer(_controller),
                                  ),
                          Center(
                            child: _controller.value.isPlaying
                                ? AnimatedOpacity(
                                    opacity: _btnOpacity,
                                    duration: const Duration(milliseconds: 300),
                                    child: IconButton(
                                      onPressed: () {
                                        _controller.pause();
                                        setState(() {});
                                      },
                                      icon: const Icon(
                                        Icons.pause,
                                        color: Colors.white,
                                        shadows: [Shadow(color: Colors.black, blurRadius: 32)],
                                      ),
                                      iconSize: 72,
                                    ),
                                  )
                                : IconButton(
                                    onPressed: _play,
                                    icon: const Icon(
                                      Icons.play_arrow,
                                      color: Colors.white,
                                      shadows: [Shadow(color: Colors.black, blurRadius: 32)],
                                    ),
                                    iconSize: 72,
                                  ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
            // Configuration toolbar: Fit vs Fill and Playback speed
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16.0),
              child: Row(
                children: [
                  Expanded(
                    child: SegmentedButton<String>(
                      showSelectedIcon: false,
                      segments: const [
                        ButtonSegment(
                          value: 'fit',
                          icon: Icon(Icons.fit_screen, size: 18),
                          label: Text("Ajustar", style: TextStyle(fontSize: 12)),
                        ),
                        ButtonSegment(
                          value: 'fill',
                          icon: Icon(Icons.crop_square, size: 18),
                          label: Text("Llenar 1:1", style: TextStyle(fontSize: 12)),
                        ),
                      ],
                      selected: {_fitMode},
                      onSelectionChanged: (set) {
                        setState(() {
                          _fitMode = set.first;
                          HapticFeedback.lightImpact();
                        });
                      },
                    ),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    ),
                    onPressed: _cycleSpeed,
                    icon: const Icon(Icons.speed, size: 18),
                    label: Text(
                      "${_playbackSpeed}x",
                      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            // Trimming slider
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Stack(
                  children: [
                    RangeSlider(
                      year2023: false,
                      values: _range,
                      onChangeEnd: (_) async {
                        if (_seekTarget == _controller.value.duration * _range.end) {
                          _requestSeek(_controller.value.duration * _range.end - const Duration(seconds: 1));
                          if (_seekTarget < _controller.value.duration * _range.start) {
                            _seekTarget = _controller.value.duration * _range.start;
                          }
                        }
                        _play();
                        setState(() {});
                        await Future.delayed(const Duration(milliseconds: 200));
                        setState(() {});
                        _editing = false;
                      },
                      onChangeStart: (_) {
                        _controller.pause();
                        _editing = true;
                      },
                      onChanged: (values) {
                        final Duration seekTarget;
                        if (_range.start != values.start) {
                          seekTarget = _controller.value.duration * values.start;
                        } else if (_range.end != values.end) {
                          seekTarget = _controller.value.duration * values.end;
                        } else {
                          return;
                        }
                        _requestSeek(seekTarget);
                        _range = values;
                        setState(() {});
                      },
                    ),
                    if (!_editing && _ready && _controller.value.duration.inMilliseconds > 0)
                      IgnorePointer(
                        child: Slider(
                          thumbColor: theme.colorScheme.onSurface,
                          activeColor: Colors.transparent,
                          inactiveColor: Colors.transparent,
                          value: (_controller.value.position.inMilliseconds /
                                  _controller.value.duration.inMilliseconds)
                              .clamp(0.0, 1.0),
                          onChanged: (_) {},
                          year2023: false,
                        ),
                      ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
                  child: Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _ready && !_exporting ? _captureFrameAsStatic : null,
                          icon: const Icon(Icons.camera_alt_outlined),
                          label: const Text("Fotograma fijo"),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        flex: 2,
                        child: FilledButton(
                          clipBehavior: Clip.antiAlias,
                          style: ButtonStyle(
                            padding: WidgetStateProperty.all(EdgeInsets.zero),
                          ),
                          onPressed: _exporting ? null : () => doCrop(),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const SizedBox(height: 8),
                              Text(l10n.done),
                              const SizedBox(height: 8),
                              if (_exporting)
                                StreamBuilder<Progress>(
                                  stream: service.progressStream,
                                  builder: (context, asyncSnapshot) {
                                    return LinearProgressIndicator(
                                      value: asyncSnapshot.data?.progress,
                                    );
                                  },
                                ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  bool _canSeek = true;

  void _requestSeek(Duration time) async {
    _seekTarget = time;
    if (_canSeek) {
      _canSeek = false;
      await _controller.seekTo(time);
      await Future.delayed(const Duration(milliseconds: 100));
      _canSeek = true;
      if (_seekTarget != time) {
        _requestSeek(_seekTarget);
      }
    }
  }

  void _play() {
    _controller.play();
    _btnOpacity = 1;
    setState(() {});
    Future.delayed(const Duration(seconds: 1)).then((_) {
      if (mounted) {
        _btnOpacity = 0;
        setState(() {});
      }
    });
  }

  final CropAndScaleService service = CropAndScaleService();
  bool _editing = false;

  Future<void> doCrop() async {
    setState(() {
      _exporting = true;
    });
    try {
      _controller.pause();
      final output = "$mediaCacheDir/import_${DateTime.now().millisecondsSinceEpoch}.mp4";
      final started = await service.start(
        inputFile: widget.imagePath,
        outputFile: output,
        start: _controller.value.duration * _range.start,
        end: _controller.value.duration * _range.end,
      );
      Status? result = started ? null : Status.FAILED;
      if (started) {
        await for (final s in service.progressStream) {
          if (s.status == Status.SUCCESS || s.status == Status.FAILED || s.status == Status.CANCELLED) {
            result = s.status;
            break;
          }
        }
      }
      if (result != Status.SUCCESS) {
        debugPrint("Transcoding failed!");
        if (mounted) {
          showDialog(
            context: context,
            builder: (context) {
              return ErrorDialog(
                title: AppLocalizations.of(context)!.trimFailed,
                message: AppLocalizations.of(context)!.trimFailedMsg,
              );
            },
          );
        }
        return;
      }
      if (!mounted) return;
      Navigator.of(context).pushNamed(
        "/edit",
        arguments: EditArguments(
          pack: widget.pack,
          index: widget.index,
          mediaPath: output,
          type: MediaType.video,
          fitMode: _fitMode,
        ),
      );
    } finally {
      if (mounted) {
        setState(() {
          _exporting = false;
        });
      }
    }
  }
}
