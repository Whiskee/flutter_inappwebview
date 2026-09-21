import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_android/flutter_inappwebview_android.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

/// What the runtime owner does with the headless side it just handed over,
/// and from which callback.
///
/// Acting on a successful acknowledgement is inside the documented contract:
/// onAttachResult is "delivered before ordinary onWebViewCreated"
/// (`platform_inappwebview_widget.dart:235-238`).
///
/// The three `*InAttachResult` release shapes are every route a retired
/// instance still offers to the channel `flutter_inappwebview_<runtime id>`,
/// which the presentation now shares with it. They must all be inert.
enum _Release {
  /// The documented release.
  disposeInAttachResult,

  /// `webViewController` is a public getter on PlatformHeadlessInAppWebView.
  controllerInAttachResult,

  /// `run()` gates re-entry on `_started`, which retirement clears.
  rerunInAttachResult,

  /// The same documented release, one callback later.
  disposeInWebViewCreated,

  /// The host tears the presentation down instead of the headless side.
  widgetInAttachResult,

  never,
}

/// How the mock answers `isAttached`.
///
/// [nativeGate] models exactly one clause of the real gate in
/// `android/src/main/java/.../webview/FlutterWebViewFactory.java:40-59` - that
/// a strict headless takeover needs the requested headless id to also be the
/// retention key. Production additionally requires the headless instance to
/// exist, `target.webView != null`, `!target.attachOnlyClaimed`, and on the
/// keep-alive branch `keepAliveId.equals(target.keepAliveId)`. The fixture is
/// therefore *more permissive* than native, which is the harmless direction
/// here: it grants takeovers native would refuse, so no test below can pass
/// only because the fixture refused something.
enum _Fixture { nativeGate, alwaysTrue }

void main() {
  // The reviewed pair, widened to every release route a retired instance
  // still exposes. Same handover, same outcome required.
  for (final release in [
    _Release.disposeInWebViewCreated,
    _Release.disposeInAttachResult,
    _Release.controllerInAttachResult,
    _Release.rerunInAttachResult,
  ]) {
    testWidgets(
      'a headless release via ${release.name} keeps the new bridge reachable',
      (tester) async {
        final probe = await _handover(tester, release: release);
        expect(probe.outcomes, [true]);
        expect(probe.ready, 1);
        expect(
          probe.boundId,
          probe.submitted!['keepAliveId'],
          reason: 'the new owner binds the transferred runtime id',
        );
        // '"old"' would mean the presented controller never claimed the
        // channel; null would mean something unregistered its handler.
        expect(
          probe.probed,
          '"new"',
          reason:
              'the controller handed to onWebViewCreated must stay reachable '
              'on the transferred runtime id, whatever the runtime owner did '
              'with the headless instance it handed over',
        );
        // Recoverability of the terminal state: the runtime is retired on the
        // headless side and live on the presented side, not both or neither.
        expect(probe.headlessRunning, isFalse);
        // A retired instance must not reach native either: the runtime it
        // would be releasing is no longer its own.
        expect(probe.headlessNativeDisposes, 0);
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
  // that controller - and must be left with the same ownership an ordinary
  // teardown leaves.
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
    // Terminal ownership, and the reason this acknowledgement sits after the
    // controller construction rather than before it. Native has already
    // handed the runtime to the platform view, so the headless instance must
    // not still be the one answering for it: '"old"' here would mean the
    // handover was acknowledged while the runtime still belonged, on the Dart
    // side, to an instance native had already given up.
    expect(
      probe.probed,
      isNull,
      reason:
          'the runtime channel must be held by this widget/keep-alive '
          'controller, not by the headless instance it replaced',
    );
    expect(probe.headlessRunning, isFalse);
  });

  testWidgets('an ordinary presentation reports no attachment outcome', (
    tester,
  ) async {
    final probe = await _handover(
      tester,
      release: _Release.disposeInWebViewCreated,
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
    expect(
      probe.probed,
      '"old"',
      reason: 'and must leave its bridge answering',
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
  // The pre-handover bridge. Registering it is what lets a probe tell "the
  // presented controller owns the channel" ('"new"') from "the headless
  // instance still owns it" ('"old"') from "nobody does" (null) - three
  // states a bare null check collapses into one.
  (headless.webViewController as PlatformInAppWebViewController?)
      ?.addJavaScriptHandler(handlerName: 'probe', callback: (_) => 'old');

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
        switch (release) {
          case _Release.disposeInAttachResult:
            unawaited(headless.dispose());
          case _Release.controllerInAttachResult:
            headless.webViewController?.dispose();
          case _Release.rerunInAttachResult:
            unawaited(headless.run());
          case _Release.widgetInAttachResult:
            disposeInReceipt!();
          case _Release.disposeInWebViewCreated:
          case _Release.never:
            break;
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
        if (release == _Release.disposeInWebViewCreated) {
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
