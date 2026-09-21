import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_ios/flutter_inappwebview_ios.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'headless dispose releases a native instance whose run receipt failed',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      IOSInAppWebViewPlatform.registerWith();
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final headless = IOSHeadlessInAppWebView(
        IOSHeadlessInAppWebViewCreationParams(),
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
      final unregistered = IOSHeadlessInAppWebView(
        IOSHeadlessInAppWebViewCreationParams(),
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
}
