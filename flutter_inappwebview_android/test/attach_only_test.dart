import 'dart:typed_data';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_android/flutter_inappwebview_android.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  for (final detached in [false, true]) {
    testWidgets(
      'successful native transfer with detached=$detached keeps its acknowledgement',
      (tester) async {
        AndroidInAppWebViewPlatform.registerWith();
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        final reply = Completer<bool>();
        final outcomes = <bool>[];
        var submitted = 0;
        var ready = 0;
        messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
          call,
        ) async {
          if (call.method == 'create') {
            final args = call.arguments as Map;
            messenger.setMockMethodCallHandler(
              MethodChannel(
                'com.pichillilorenzo/flutter_inappwebview_attach_${args['id']}',
              ),
              (_) => reply.future,
            );
          }
          return null;
        });
        final view = AndroidInAppWebViewWidget(
          PlatformInAppWebViewWidgetCreationParams(
            attachOnly: true,
            keepAlive: InAppWebViewKeepAlive(),
            initialSettings: InAppWebViewSettings(useHybridComposition: true),
            onAttachStart: () => submitted++,
            onAttachResult: outcomes.add,
            onWebViewCreated: (_) => ready++,
          ),
        );
        await tester.pumpWidget(
          MaterialApp(home: Builder(builder: view.build)),
        );
        await tester.pump();
        expect(submitted, 1);
        expect(outcomes, isEmpty);
        expect(ready, 0);
        if (detached) view.dispose();
        reply.complete(true);
        await tester.pump();
        expect(outcomes, [true]);
        expect(ready, detached ? 0 : 1);
        if (!detached) view.dispose();
        await tester.pumpWidget(const SizedBox());
        messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
      },
    );
  }
  testWidgets(
    'native unavailable is observed without ordinary ready callback',
    (tester) async {
      AndroidInAppWebViewPlatform.registerWith();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final outcomes = <bool>[];
      var ready = 0;
      Map<dynamic, dynamic>? submitted;
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
        call,
      ) async {
        if (call.method == 'create') {
          final args = call.arguments as Map;
          submitted =
              const StandardMessageCodec().decodeMessage(
                    ByteData.sublistView(args['params'] as Uint8List),
                  )
                  as Map;
          messenger.setMockMethodCallHandler(
            MethodChannel(
              'com.pichillilorenzo/flutter_inappwebview_attach_${args['id']}',
            ),
            (call) async => false,
          );
        }
        return null;
      });
      final view = AndroidInAppWebViewWidget(
        PlatformInAppWebViewWidgetCreationParams(
          attachOnly: true,
          keepAlive: InAppWebViewKeepAlive(),
          initialSettings: InAppWebViewSettings(useHybridComposition: true),
          onAttachResult: outcomes.add,
          onWebViewCreated: (_) => ready++,
        ),
      );
      await tester.pumpWidget(MaterialApp(home: Builder(builder: view.build)));
      await tester.pump();
      await tester.pump();
      expect(submitted!['attachOnly'], true);
      expect(outcomes, [false]);
      expect(ready, 0);
      view.dispose();
      await tester.pumpWidget(const SizedBox());
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
    },
  );
}
