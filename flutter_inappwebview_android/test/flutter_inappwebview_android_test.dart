import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_android/flutter_inappwebview_android.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const startupChannel = MethodChannel(
    'com.pichillilorenzo/flutter_inappwebview_webviewfeature',
  );
  const controllerChannel = MethodChannel(
    'com.pichillilorenzo/flutter_inappwebview_bridge-test',
  );
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(startupChannel, null);
    messenger.setMockMethodCallHandler(controllerChannel, null);
  });

  test('startUpWebView waits for the native startup result', () async {
    final nativeResult = Completer<void>();
    final calls = <String>[];
    messenger.setMockMethodCallHandler(startupChannel, (call) async {
      calls.add(call.method);
      await nativeResult.future;
      return null;
    });
    var finished = false;
    final pending = AndroidWebViewFeature.static().startUpWebView().then(
      (_) => finished = true,
    );
    await Future<void>.delayed(Duration.zero);
    expect(calls, ['startUpWebView']);
    expect(finished, isFalse);
    nativeResult.complete();
    await pending;
    expect(finished, isTrue);
  });

  test('readiness waits for the native registration result', () async {
    final nativeResult = Completer<bool>();
    final calls = <String>[];
    messenger.setMockMethodCallHandler(controllerChannel, (call) {
      calls.add(call.method);
      return nativeResult.future;
    });
    final controller = AndroidInAppWebViewController(
      const AndroidInAppWebViewControllerCreationParams(id: 'bridge-test'),
    );
    addTearDown(controller.dispose);
    var finished = false;
    final pending = controller.waitForInitialJavaScriptBridgeReady().then(
      (_) => finished = true,
    );
    await Future<void>.delayed(Duration.zero);
    expect(calls, ['waitForInitialJavaScriptBridgeReady']);
    expect(finished, isFalse);
    nativeResult.complete(true);
    await pending;
    expect(finished, isTrue);
  });

  test(
    'readiness propagates native disposal and registration errors',
    () async {
      messenger.setMockMethodCallHandler(controllerChannel, (call) async {
        throw PlatformException(
          code: 'bridge_registration',
          message: 'WebView was disposed before bridge registration',
        );
      });
      final controller = AndroidInAppWebViewController(
        const AndroidInAppWebViewControllerCreationParams(id: 'bridge-test'),
      );
      addTearDown(controller.dispose);
      await expectLater(
        controller.waitForInitialJavaScriptBridgeReady(),
        throwsA(
          isA<PlatformException>().having(
            (error) => error.code,
            'code',
            'bridge_registration',
          ),
        ),
      );
    },
  );
}
