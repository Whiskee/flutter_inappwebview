import 'dart:typed_data';
import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_android/flutter_inappwebview_android.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'headless dispose treats a transferred native channel as consumed',
    () async {
      AndroidInAppWebViewPlatform.registerWith();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(
        const MethodChannel(
          'com.pichillilorenzo/flutter_headless_inappwebview',
        ),
        (_) async => true,
      );
      final headless = AndroidHeadlessInAppWebView(
        AndroidHeadlessInAppWebViewCreationParams(),
      );
      await headless.run();
      messenger.setMockMethodCallHandler(
        MethodChannel(
          'com.pichillilorenzo/flutter_headless_inappwebview_${headless.id}',
        ),
        (_) async => throw MissingPluginException(
          'native headless owner was transferred to a platform view',
        ),
      );

      await expectLater(headless.dispose(), completes);
      expect(headless.isRunning(), isFalse);

      messenger.setMockMethodCallHandler(
        const MethodChannel(
          'com.pichillilorenzo/flutter_headless_inappwebview',
        ),
        null,
      );
    },
  );

  for (final strict in [false, true]) {
    testWidgets('controller retention is opt-in: attachOnly=$strict', (
      tester,
    ) async {
      AndroidInAppWebViewPlatform.registerWith();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final keepAlive = InAppWebViewKeepAlive();
      final controllers = <PlatformInAppWebViewController>[];
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
        call,
      ) async {
        if (call.method == 'create') {
          final id = (call.arguments as Map)['id'];
          messenger.setMockMethodCallHandler(
            MethodChannel(
              'com.pichillilorenzo/flutter_inappwebview_attach_$id',
            ),
            (_) async => true,
          );
        }
        return null;
      });
      AndroidInAppWebViewWidget presentation(String value) =>
          AndroidInAppWebViewWidget(
            PlatformInAppWebViewWidgetCreationParams(
              attachOnly: strict,
              keepAlive: keepAlive,
              initialSettings: InAppWebViewSettings(useHybridComposition: true),
              onWebViewCreated: (controller) {
                final actual = controller as PlatformInAppWebViewController;
                controllers.add(actual);
                actual.addJavaScriptHandler(
                  handlerName: 'probe',
                  callback: (_) => value,
                );
              },
            ),
          );
      final first = presentation('first');
      await tester.pumpWidget(MaterialApp(home: Builder(builder: first.build)));
      await tester.pump();
      expect(await _probe(keepAlive.id), '"first"');
      first.dispose();
      await tester.pumpWidget(const SizedBox());
      expect(await _probe(keepAlive.id), strict ? '"first"' : null);
      if (strict) {
        final second = presentation('second');
        await tester.pumpWidget(
          MaterialApp(home: Builder(builder: second.build)),
        );
        await tester.pump();
        // A delayed old presenter/controller disposal cannot remove the new handler.
        controllers.first.dispose(isKeepAlive: true);
        expect(await _probe(keepAlive.id), '"second"');
        messenger.setMockMethodCallHandler(
          const MethodChannel(
            'com.pichillilorenzo/flutter_inappwebview_manager',
          ),
          (_) async => throw PlatformException(code: 'native_close_failed'),
        );
        await expectLater(
          AndroidInAppWebViewController.static().disposeKeepAlive(keepAlive),
          throwsA(isA<PlatformException>()),
        );
        expect(await _probe(keepAlive.id), null);
        second.dispose();
        await tester.pumpWidget(const SizedBox());
      }
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
    });
  }
  testWidgets('query transport failure stays unknown, not native miss', (
    tester,
  ) async {
    AndroidInAppWebViewPlatform.registerWith();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final outcomes = <bool?>[];
    var ready = 0;
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
      call,
    ) async {
      if (call.method == 'create') {
        final id = (call.arguments as Map)['id'];
        messenger.setMockMethodCallHandler(
          MethodChannel('com.pichillilorenzo/flutter_inappwebview_attach_$id'),
          (_) async => throw PlatformException(code: 'reply_lost'),
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
    expect(outcomes, [null]);
    expect(ready, 0);
    view.dispose();
    await tester.pumpWidget(const SizedBox());
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
  });
  for (final detached in [false, true]) {
    testWidgets(
      'successful native transfer with detached=$detached keeps its acknowledgement',
      (tester) async {
        AndroidInAppWebViewPlatform.registerWith();
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        final reply = Completer<bool>();
        final outcomes = <bool?>[];
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
        if (detached) {
          view.dispose();
          expect(outcomes, [null]);
        }
        reply.complete(true);
        await tester.pump();
        expect(outcomes, [if (detached) null else true]);
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
      final outcomes = <bool?>[];
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

Future<dynamic> _probe(String id) async {
  const codec = StandardMethodCodec();
  final result = Completer<dynamic>();
  await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
        'com.pichillilorenzo/flutter_inappwebview_$id',
        codec.encodeMethodCall(
          MethodCall('onCallJsHandler', {
            'handlerName': 'probe',
            'data': {
              'origin': 'https://example.com',
              'requestUrl': 'https://example.com',
              'isMainFrame': true,
              'args': jsonEncode([]),
            },
          }),
        ),
        (reply) =>
            result.complete(reply == null ? null : codec.decodeEnvelope(reply)),
      );
  return result.future;
}
