import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_android/flutter_inappwebview_android.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

/// Where the runtime owner releases the headless side it just handed over.
///
/// Both are inside the documented contract: onAttachResult is "delivered
/// before ordinary onWebViewCreated"
/// (`platform_inappwebview_widget.dart:235-238`), so a host is entitled to act
/// on a successful acknowledgement in either callback.
enum _Release { inAttachResult, inWebViewCreated, never, widgetInAttachResult }

/// How faithfully the mock answers `isAttached`.
///
/// [nativeGate] replicates the real refusal in
/// `android/src/main/java/.../webview/FlutterWebViewFactory.java:40-59`: a
/// strict headless takeover is granted only when the requested headless id is
/// also the retention key. Keeping the fixture on that gate is what stops this
/// suite from proving something about a configuration native rejects.
enum _Fixture { nativeGate, alwaysTrue }

void main() {
  // The reviewed pair. Same handover, same host action, only the callback it
  // runs in differs.
  for (final release in [_Release.inWebViewCreated, _Release.inAttachResult]) {
    testWidgets(
      'a headless release in ${release.name} keeps the new bridge reachable',
      (tester) async {
        final probe = await _handover(tester, release: release);
        expect(probe.outcomes, [true]);
        expect(probe.ready, 1);
        expect(
          probe.boundId,
          probe.submitted!['keepAliveId'],
          reason: 'the new owner binds the transferred runtime id',
        );
        expect(
          probe.probed,
          '"new"',
          reason:
              'the controller handed to onWebViewCreated must stay reachable '
              'on the transferred runtime id, whichever callback released the '
              'headless owner',
        );
        // The mechanism behind that symptom: AndroidHeadlessInAppWebView
        // .dispose() early-returns on `!_started`, and the handover clears
        // `_started` through internalDispose(). A release that still reaches
        // native is a release that also ran `_webViewController?.dispose()`,
        // which unregisters the method call handler on
        // `flutter_inappwebview_<runtime id>` - the channel the new owner
        // shares with the headless owner it replaced.
        expect(
          probe.headlessNativeDisposes,
          0,
          reason:
              'the handover retires the headless owner before acknowledging '
              'it, so the host release is the no-op it is in onWebViewCreated',
        );
        // Recoverability of the new terminal state: the runtime is retired on
        // the headless side and live on the presented side, not both or
        // neither.
        expect(probe.headlessRunning, isFalse);
      },
    );
  }

  testWidgets('the acknowledgement still precedes onWebViewCreated', (
    tester,
  ) async {
    final probe = await _handover(tester, release: _Release.never);
    expect(
      probe.order,
      ['attachResult', 'webViewCreated'],
      reason:
          'onAttachResult is documented as delivered before ordinary '
          'onWebViewCreated; establishing the owner first must not reorder it',
    );
  });

  // Establishing the owner before acknowledging means the acknowledgement now
  // runs with a controller already published on the field, so a host that
  // tears the presentation down inside the receipt must not then be handed
  // that controller.
  testWidgets('a teardown inside the receipt withholds the ready callback', (
    tester,
  ) async {
    final probe = await _handover(
      tester,
      release: _Release.widgetInAttachResult,
    );
    expect(probe.outcomes, [true]);
    expect(
      probe.ready,
      0,
      reason:
          'the presentation is gone, so onWebViewCreated must not deliver its '
          'controller',
    );
    expect(probe.order, ['attachResult']);
  });

  testWidgets('an ordinary presentation reports no attachment outcome', (
    tester,
  ) async {
    final probe = await _handover(
      tester,
      release: _Release.inWebViewCreated,
      strict: false,
    );
    expect(
      probe.outcomes,
      isEmpty,
      reason:
          'only attachOnly owes an acknowledgement; an ordinary handover has '
          'no native transfer to acknowledge',
    );
    expect(probe.ready, 1);
    expect(probe.probed, '"new"');
  });

  testWidgets('a native miss is acknowledged without establishing an owner', (
    tester,
  ) async {
    final probe = await _handover(
      tester,
      release: _Release.never,
      fixture: _Fixture.nativeGate,
      withKeepAlive: false,
    );
    expect(probe.submitted!['attachOnly'], true);
    expect(probe.submitted!['headlessWebViewId'], isNotNull);
    expect(
      probe.submitted!['keepAliveId'],
      isNull,
      reason:
          'FlutterWebViewFactory grants a strict headless takeover only when '
          'headlessWebViewId equals keepAliveId',
    );
    expect(
      probe.outcomes,
      [false],
      reason:
          'a refusal has no owner to establish, so it is acknowledged where '
          'it is observed',
    );
    expect(probe.ready, 0);
    expect(probe.order, ['attachResult']);
    expect(
      probe.headlessRunning,
      isTrue,
      reason: 'a refused takeover must leave the headless runtime untouched',
    );
  });
}

class _Probe {
  _Probe({
    required this.outcomes,
    required this.ready,
    required this.probed,
    required this.submitted,
    required this.boundId,
    required this.headlessNativeDisposes,
    required this.headlessRunning,
    required this.order,
  });
  final List<bool?> outcomes;
  final int ready;
  final dynamic probed;
  final Map<dynamic, dynamic>? submitted;
  final dynamic boundId;
  final int headlessNativeDisposes;
  final bool headlessRunning;
  final List<String> order;
}

Future<_Probe> _handover(
  WidgetTester tester, {
  required _Release release,
  _Fixture fixture = _Fixture.nativeGate,
  bool withKeepAlive = true,
  bool strict = true,
}) async {
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
  expect(headless.isRunning(), isTrue);

  var headlessNativeDisposes = 0;
  messenger.setMockMethodCallHandler(
    MethodChannel(
      'com.pichillilorenzo/flutter_headless_inappwebview_${headless.id}',
    ),
    (call) async {
      if (call.method == 'dispose') headlessNativeDisposes++;
      return null;
    },
  );

  final outcomes = <bool?>[];
  final order = <String>[];
  var ready = 0;
  Map<dynamic, dynamic>? submitted;
  dynamic boundId;
  void Function()? disposeInReceipt;

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
      final bool answer;
      switch (fixture) {
        case _Fixture.alwaysTrue:
          answer = true;
        case _Fixture.nativeGate:
          final headlessWebViewId = submitted!['headlessWebViewId'] as String?;
          final keepAliveId = submitted!['keepAliveId'] as String?;
          answer = headlessWebViewId != null
              ? headlessWebViewId == keepAliveId
              : keepAliveId != null;
      }
      messenger.setMockMethodCallHandler(
        MethodChannel(
          'com.pichillilorenzo/flutter_inappwebview_attach_${args['id']}',
        ),
        (_) async => answer,
      );
    }
    return null;
  });

  final view = AndroidInAppWebViewWidget(
    PlatformInAppWebViewWidgetCreationParams(
      attachOnly: strict,
      headlessWebView: headless,
      keepAlive: withKeepAlive ? InAppWebViewKeepAlive() : null,
      initialSettings: InAppWebViewSettings(useHybridComposition: true),
      onAttachResult: (attached) {
        order.add('attachResult');
        outcomes.add(attached);
        if (attached != true) return;
        if (release == _Release.inAttachResult) {
          unawaited(headless.dispose());
        }
        if (release == _Release.widgetInAttachResult) {
          disposeInReceipt!();
        }
      },
      onWebViewCreated: (controller) {
        order.add('webViewCreated');
        ready++;
        final actual = controller as PlatformInAppWebViewController;
        boundId = actual.getViewId();
        actual.addJavaScriptHandler(
          handlerName: 'probe',
          callback: (_) => 'new',
        );
        if (release == _Release.inWebViewCreated) {
          unawaited(headless.dispose());
        }
      },
    ),
  );
  disposeInReceipt = view.dispose;
  await tester.pumpWidget(MaterialApp(home: Builder(builder: view.build)));
  await tester.pump();
  await tester.pump();

  final result = _Probe(
    outcomes: outcomes,
    ready: ready,
    probed: await _probe(headless.id),
    submitted: submitted,
    boundId: boundId,
    headlessNativeDisposes: headlessNativeDisposes,
    headlessRunning: headless.isRunning(),
    order: order,
  );

  if (release != _Release.widgetInAttachResult) view.dispose();
  await tester.pumpWidget(const SizedBox());
  messenger.setMockMethodCallHandler(SystemChannels.platform_views, null);
  messenger.setMockMethodCallHandler(
    const MethodChannel('com.pichillilorenzo/flutter_headless_inappwebview'),
    null,
  );
  return result;
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
