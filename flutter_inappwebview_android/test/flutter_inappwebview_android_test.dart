import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_inappwebview_android/flutter_inappwebview_android.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('startUpWebView waits for the native startup channel result', () async {
    const channel = MethodChannel(
      'com.pichillilorenzo/flutter_inappwebview_webviewfeature',
    );
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return null;
        });

    await AndroidWebViewFeature.static().startUpWebView();

    expect(calls.map((call) => call.method), <String>['startUpWebView']);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('registers the Android JavaScript interface from the posted task', () {
    final source = File(
      'android/src/main/java/com/pichillilorenzo/'
      'flutter_inappwebview_android/webview/in_app_webview/InAppWebView.java',
    ).readAsStringSync();
    final prepareIndex = source.indexOf('public void prepare()');
    final postIndex = source.indexOf('post(new Runnable()', prepareIndex);
    final registrationIndex = source.indexOf(
      'addJavascriptInterface(javaScriptBridgeInterface',
      postIndex,
    );

    expect(prepareIndex, greaterThanOrEqualTo(0));
    expect(postIndex, greaterThan(prepareIndex));
    expect(registrationIndex, greaterThan(postIndex));
  });

  test('queues the initial load after native bridge registrations', () {
    final source = File(
      'android/src/main/java/com/pichillilorenzo/'
      'flutter_inappwebview_android/webview/in_app_webview/FlutterWebView.java',
    ).readAsStringSync();
    final initialLoadIndex = source.indexOf('public void makeInitialLoad');
    final postIndex = source.indexOf(
      'webView.post(new Runnable()',
      initialLoadIndex,
    );
    final fileLoadIndex = source.indexOf(
      'webView.loadFile(initialFile)',
      postIndex,
    );
    final dataLoadIndex = source.indexOf(
      'webView.loadDataWithBaseURL',
      postIndex,
    );
    final urlLoadIndex = source.indexOf(
      'webView.loadUrl(urlRequest)',
      postIndex,
    );

    expect(initialLoadIndex, greaterThanOrEqualTo(0));
    expect(postIndex, greaterThan(initialLoadIndex));
    expect(fileLoadIndex, greaterThan(postIndex));
    expect(dataLoadIndex, greaterThan(postIndex));
    expect(urlLoadIndex, greaterThan(postIndex));
  });

  test('plugin script registration is cancellable and retries startup', () {
    final source = File(
      'android/src/main/java/com/pichillilorenzo/'
      'flutter_inappwebview_android/types/UserContentController.java',
    ).readAsStringSync();
    final pluginMethodIndex = source.indexOf(
      'public boolean addPluginScript(final PluginScript pluginScript)',
    );
    final postIndex = source.indexOf(
      'webView.post(new Runnable()',
      pluginMethodIndex,
    );
    final registrationIndex = source.indexOf(
      'WebViewCompat.addDocumentStartJavaScript',
      postIndex,
    );
    final tokenIndex = source.indexOf(
      'pendingPluginScriptRegistrations.put',
      pluginMethodIndex,
    );
    final tokenGuardIndex = source.indexOf(
      'pendingPluginScriptRegistrations.get(pluginScript) == registrationToken',
      tokenIndex,
    );
    final retryIndex = source.indexOf(
      'retryPluginScriptAfterStartup',
      registrationIndex,
    );
    final removeMethodIndex = source.indexOf(
      'public boolean removePluginScript',
    );
    final removeIndex = source.indexOf(
      'this.pendingPluginScriptRegistrations.remove(pluginScript)',
      removeMethodIndex,
    );
    final logicalRemoveIndex = source.indexOf(
      'this.pluginScripts.get(pluginScript.getInjectionTime()).remove',
      removeMethodIndex,
    );

    expect(pluginMethodIndex, greaterThanOrEqualTo(0));
    expect(postIndex, greaterThan(pluginMethodIndex));
    expect(tokenIndex, greaterThan(pluginMethodIndex));
    expect(tokenGuardIndex, greaterThan(tokenIndex));
    expect(registrationIndex, greaterThan(postIndex));
    expect(retryIndex, greaterThan(registrationIndex));
    expect(removeIndex, greaterThan(retryIndex));
    expect(logicalRemoveIndex, greaterThan(removeIndex));
    expect(removeIndex, greaterThan(removeMethodIndex));
  });

  test('exposes a native queue barrier before the first URL load', () {
    final source = File(
      'android/src/main/java/com/pichillilorenzo/'
      'flutter_inappwebview_android/webview/WebViewChannelDelegate.java',
    ).readAsStringSync();
    final methodIndex = source.indexOf(
      'case waitForInitialJavaScriptBridgeReady:',
    );
    final postIndex = source.indexOf(
      'expectedWebView.post(new Runnable()',
      methodIndex,
    );
    final registrationWaitIndex = source.indexOf(
      '.runWhenScriptRegistrationsComplete',
      postIndex,
    );
    final registrationErrorIndex = source.indexOf(
      '.getScriptRegistrationError()',
      registrationWaitIndex,
    );
    final resultIndex = source.indexOf(
      'result.success(true)',
      registrationErrorIndex,
    );

    expect(methodIndex, greaterThanOrEqualTo(0));
    expect(postIndex, greaterThan(methodIndex));
    expect(registrationWaitIndex, greaterThan(postIndex));
    expect(registrationErrorIndex, greaterThan(registrationWaitIndex));
    expect(resultIndex, greaterThan(registrationErrorIndex));
  });
}
