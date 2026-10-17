import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_ios/flutter_inappwebview_ios.dart';
import 'package:flutter_inappwebview_platform_interface/flutter_inappwebview_platform_interface.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const channel = MethodChannel(
    'com.pichillilorenzo/flutter_inappwebview_retained',
  );
  Future<ByteData?> deliver() async {
    final result = Completer<ByteData?>();
    messenger.handlePlatformMessage(
      channel.name,
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall('onConsoleMessage', <String, dynamic>{}),
      ),
      result.complete,
    );
    return result.future;
  }

  IOSInAppWebViewController controller() => IOSInAppWebViewController(
    const IOSInAppWebViewControllerCreationParams(id: 'retained'),
  );

  test(
    'presentation release then final disposal unregisters once without disposed-channel assertion',
    () async {
      final old = controller();
      old.dispose(isKeepAlive: true);
      expect(() => old.dispose(), returnsNormally);
      expect(() => old.dispose(), returnsNormally);
      expect(await deliver(), isNull);
    },
  );
  test('old finalization cannot unregister the successor handler', () async {
    final old = controller();
    old.dispose(isKeepAlive: true);
    final next = controller();
    old.dispose();
    expect(await deliver(), isNotNull);
    next.dispose();
    expect(await deliver(), isNull);
  });
  test(
    'runtime reclaim registers the old wrapper as the actual channel owner',
    () async {
      final runtime = controller();
      final presentation = controller();
      presentation.dispose(isKeepAlive: true);
      final ChannelController owner = runtime;
      owner.initMethodCallHandler();
      presentation.dispose();
      expect(await deliver(), isNotNull);
      runtime.dispose();
      expect(await deliver(), isNull);
    },
  );
}
