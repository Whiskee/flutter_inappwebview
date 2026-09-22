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

  test(
    'headless dispose releases a native instance whose run receipt failed',
    () async {
      AndroidInAppWebViewPlatform.registerWith();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final headless = AndroidHeadlessInAppWebView(
        AndroidHeadlessInAppWebViewCreationParams(),
      );
      // Native registers the instance and its channel before `run` fails.
      var nativeDisposes = 0;
      messenger.setMockMethodCallHandler(
        MethodChannel(
          'com.pichillilorenzo/flutter_headless_inappwebview_${headless.id}',
        ),
        (call) async {
          if (call.method == 'dispose') nativeDisposes++;
          return null;
        },
      );
      messenger.setMockMethodCallHandler(
        const MethodChannel(
          'com.pichillilorenzo/flutter_headless_inappwebview',
        ),
        (_) async => throw PlatformException(code: 'run_failed'),
      );

      await expectLater(headless.run(), throwsA(isA<PlatformException>()));
      expect(headless.isRunning(), isFalse);

      await expectLater(headless.dispose(), completes);
      expect(
        nativeDisposes,
        1,
        reason: 'the original instance id must receive the native release',
      );

      // A run native never registered has no channel: still not an owner.
      final unregistered = AndroidHeadlessInAppWebView(
        AndroidHeadlessInAppWebViewCreationParams(),
      );
      await expectLater(unregistered.run(), throwsA(isA<PlatformException>()));
      await expectLater(unregistered.dispose(), completes);

      messenger.setMockMethodCallHandler(
        const MethodChannel(
          'com.pichillilorenzo/flutter_headless_inappwebview',
        ),
        null,
      );
    },
  );

  test('headless dispose takes its own bridge down with it', () async {
    AndroidInAppWebViewPlatform.registerWith();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel('com.pichillilorenzo/flutter_headless_inappwebview'),
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
      (_) async => null,
    );
    (headless.webViewController as PlatformInAppWebViewController?)
        ?.addJavaScriptHandler(handlerName: 'probe', callback: (_) => 'alive');
    expect(await _probe(headless.id), '"alive"');

    await headless.dispose();

    // An ordinary release - no platform view took this runtime over - has to
    // unregister the controller's handler. This is the same step that must
    // NOT run once the runtime has been handed over, so it needs an assertion
    // of its own: without one, deleting it leaves both paths green and the
    // handover regression tests lose the thing they are contrasted against.
    expect(
      await _probe(headless.id),
      isNull,
      reason: 'the released runtime must leave no bridge answering for its id',
    );

    messenger.setMockMethodCallHandler(
      const MethodChannel('com.pichillilorenzo/flutter_headless_inappwebview'),
      null,
    );
  });

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
        final keepAlive = InAppWebViewKeepAlive();
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
            keepAlive: keepAlive,
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
        // A reply that lands after the presentation is gone must not build an
        // owner behind the host's back: it was already told the outcome was
        // unknown and has drained the keep-alive side on that basis.
        expect(
          await _claimsRuntimeChannel(keepAlive.id),
          !detached,
          reason: detached
              ? 'no controller may claim the runtime channel after disposal'
              : 'the presented controller owns the runtime channel',
        );
        if (!detached) view.dispose();
        await tester.pumpWidget(const SizedBox());
        messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
      },
    );
  }
  testWidgets('a platform create that never lands still hands back an outcome', (
    tester,
  ) async {
    AndroidInAppWebViewPlatform.registerWith();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final outcomes = <bool?>[];
    var submitted = 0;
    var ready = 0;
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
      call,
    ) async {
      if (call.method == 'create') {
        throw PlatformException(code: 'platform_view_create_failed');
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
    await tester.pumpWidget(MaterialApp(home: Builder(builder: view.build)));
    await tester.pump();
    expect(submitted, 1);
    // onAttachStart already told the owner a transfer was in flight; a create
    // that dies before any native acknowledgement leaves the result lost, so
    // the owner has to hear "unknown" rather than nothing at all.
    expect(outcomes, [null]);
    expect(ready, 0);
    // The failure stays visible, but as a handled framework error rather than
    // an unhandled asynchronous one that no host could intercept.
    expect(tester.takeException(), isA<PlatformException>());
    view.dispose();
    await tester.pumpWidget(const SizedBox());
    // Disposal must not double-report on top of the outcome already delivered.
    expect(outcomes, [null]);
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
  });
  testWidgets(
    'a deferred non-hybrid create failure still hands back an outcome',
    (tester) async {
      AndroidInAppWebViewPlatform.registerWith();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final outcomes = <bool?>[];
      var submitted = 0;
      var ready = 0;
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
        call,
      ) async {
        if (call.method == 'create') {
          throw PlatformException(code: 'platform_view_create_failed');
        }
        return null;
      });
      final view = AndroidInAppWebViewWidget(
        PlatformInAppWebViewWidgetCreationParams(
          attachOnly: true,
          keepAlive: InAppWebViewKeepAlive(),
          initialSettings: InAppWebViewSettings(useHybridComposition: false),
          onAttachStart: () => submitted++,
          onAttachResult: outcomes.add,
          onWebViewCreated: (_) => ready++,
        ),
      );
      await tester.pumpWidget(MaterialApp(home: Builder(builder: view.build)));
      await tester.pump();
      expect(submitted, 1);
      expect(
        outcomes,
        [null],
        reason:
            'the size-triggered create must report the same unknown outcome '
            'as an immediate hybrid create failure',
      );
      expect(ready, 0);
      expect(tester.takeException(), isA<PlatformException>());
      view.dispose();
      await tester.pumpWidget(const SizedBox());
      expect(outcomes, [null]);
      messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
    },
  );
  testWidgets('an ordinary create failure reports no attachment outcome', (
    tester,
  ) async {
    AndroidInAppWebViewPlatform.registerWith();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final outcomes = <bool?>[];
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, (
      call,
    ) async {
      if (call.method == 'create') {
        throw PlatformException(code: 'platform_view_create_failed');
      }
      return null;
    });
    final view = AndroidInAppWebViewWidget(
      PlatformInAppWebViewWidgetCreationParams(
        attachOnly: false,
        keepAlive: InAppWebViewKeepAlive(),
        initialSettings: InAppWebViewSettings(useHybridComposition: true),
        onAttachResult: outcomes.add,
      ),
    );
    await tester.pumpWidget(MaterialApp(home: Builder(builder: view.build)));
    await tester.pump();
    // Nothing was ever submitted as a strict transfer, so there is no
    // attachment outcome to report; only attachOnly owes an acknowledgement.
    expect(outcomes, isEmpty);
    view.dispose();
    await tester.pumpWidget(const SizedBox());
    await tester.pump();
    expect(outcomes, isEmpty);
    // The create failure is still surfaced, just without an attachment outcome.
    expect(tester.takeException(), isA<PlatformException>());
    messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
  });
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

/// Whether some controller has a method call handler installed on the runtime
/// channel for [id]. An installed handler answers an unknown method by
/// throwing UnimplementedError, which comes back as an error envelope; with no
/// handler at all the reply is null.
Future<bool> _claimsRuntimeChannel(String id) async {
  const codec = StandardMethodCodec();
  final result = Completer<bool>();
  await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
        'com.pichillilorenzo/flutter_inappwebview_$id',
        codec.encodeMethodCall(const MethodCall('__unclaimedProbe__')),
        (reply) => result.complete(reply != null),
      );
  return result.future;
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
