import 'package:flutter_inappwebview_ios/src/in_app_webview/headless_in_app_webview.dart';
import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_ios/flutter_inappwebview_ios.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const instance = MethodChannel(
    'com.pichillilorenzo/flutter_inappwebview_stop-old',
  );
  const manager = MethodChannel(
    'com.pichillilorenzo/flutter_inappwebview_manager',
  );
  const headlessManager = MethodChannel(
    'com.pichillilorenzo/flutter_headless_inappwebview',
  );
  tearDown(() {
    for (final channel in [instance, manager, headlessManager]) {
      messenger.setMockMethodCallHandler(channel, null);
    }
  });
  test('native unload receipt is awaited and failure can retry', () async {
    final controller = IOSInAppWebViewController(
      const IOSInAppWebViewControllerCreationParams(id: 'stop-old'),
    );
    addTearDown(controller.dispose);
    final receipt = Completer<void>();
    var calls = 0;
    var done = false;
    messenger.setMockMethodCallHandler(instance, (call) async {
      expect(call.method, 'stopRuntimeActivity');
      calls++;
      if (calls == 1) {
        await receipt.future;
        throw PlatformException(code: 'runtime_stop_failed');
      }
      return null;
    });
    final pending = controller.stopRuntimeActivity().then((_) => done = true);
    final failed = expectLater(pending, throwsA(isA<PlatformException>()));
    await Future<void>.delayed(Duration.zero);
    expect(done, isFalse);
    receipt.complete();
    await failed;
    await controller.stopRuntimeActivity();
    expect(calls, 2);
  });
  test('exact keepAlive stop returns unknown without disposing', () async {
    final identity = InAppWebViewKeepAlive();
    final calls = <String>[];
    messenger.setMockMethodCallHandler(manager, (call) async {
      calls.add(call.method);
      expect(call.arguments['keepAliveId'], identity.id);
      return false;
    });
    expect(
      await IOSInAppWebViewController.static().stopKeepAliveRuntimeActivity(
        identity,
      ),
      isFalse,
    );
    expect(calls, ['stopKeepAliveRuntimeActivity']);
  });
  test(
    'original headless identity remains usable after native transfer',
    () async {
      final view = IOSHeadlessInAppWebView(
        IOSHeadlessInAppWebViewCreationParams(),
      );
      final calls = <String>[];
      messenger.setMockMethodCallHandler(headlessManager, (call) async {
        calls.add(call.method);
        expect(call.arguments['id'], view.id);
        return true;
      });
      await view.run();
      await view.internalDispose();
      expect(await view.stopRuntimeActivity(), isTrue);
      expect(calls, ['run', 'stopRuntimeActivity']);
    },
  );
}
