package com.pichillilorenzo.flutter_inappwebview_android;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.Collections;
import org.junit.Test;

public class WebViewFeatureManagerTest {
  @Test
  public void startupFeatureQueryWithoutActivityReturnsTypedError() {
    InAppWebViewFlutterPlugin plugin = new InAppWebViewFlutterPlugin();
    plugin.messenger = mock(BinaryMessenger.class);
    WebViewFeatureManager manager = new WebViewFeatureManager(plugin);
    MethodChannel.Result result = mock(MethodChannel.Result.class);

    manager.onMethodCall(
        new MethodCall(
            "isStartupFeatureSupported",
            Collections.singletonMap("startupFeature", "STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX")),
        result);

    verify(result).error(WebViewFeatureManager.LOG_TAG, "Activity is unavailable", null);
    verify(result, never()).success(null);
    manager.dispose();
  }
}
