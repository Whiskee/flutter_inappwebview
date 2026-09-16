package com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview;

import android.widget.FrameLayout;
import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.webview.InAppWebViewManager;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.FlutterWebView;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.InAppWebView;
import io.flutter.plugin.common.BinaryMessenger;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import java.util.HashMap;
import static org.mockito.Mockito.*;

public class HeadlessInAppWebViewTest {
  private InAppWebViewFlutterPlugin plugin() {
    InAppWebViewFlutterPlugin plugin = new InAppWebViewFlutterPlugin();
    plugin.messenger = mock(BinaryMessenger.class);
    plugin.headlessInAppWebViewManager = new HeadlessInAppWebViewManager(plugin);
    plugin.inAppWebViewManager = new InAppWebViewManager(plugin);
    return plugin;
  }

  @Test public void backgroundWakeUpWithoutActivityStillArmsChromiumVisibility() {
    InAppWebViewFlutterPlugin plugin = plugin(); // No activity: CDM/PendingIntent wake-up.
    // Layout/visibility work needs a real Android view runtime, so the runtime
    // reports no view here; arming must not depend on it either.
    FlutterWebView runtime = mock(FlutterWebView.class);
    InAppWebView nativeView = mock(InAppWebView.class);
    runtime.webView = nativeView;
    HeadlessInAppWebView headless = new HeadlessInAppWebView(plugin, "A", runtime);
    headless.prepare(new HashMap<>());
    verify(nativeView).setKeepAlwaysVisibleForChromium(true);
  }

  @Test public void retainedTakeoverRearmsChromiumVisibilityWhenPresenterReleasesIt() {
    InAppWebViewFlutterPlugin plugin = plugin();
    FlutterWebView runtime = mock(FlutterWebView.class, CALLS_REAL_METHODS);
    InAppWebView nativeView = mock(InAppWebView.class);
    runtime.webView = nativeView;
    HeadlessInAppWebView headless = new HeadlessInAppWebView(plugin, "A", runtime);
    plugin.headlessInAppWebViewManager.webViews.put("A", headless);
    try (MockedConstruction<FrameLayout.LayoutParams> params =
             mockConstruction(FrameLayout.LayoutParams.class)) {
      FlutterWebView taken = headless.disposeAndGetFlutterWebView();
      taken.keepAliveId = "A";
      plugin.inAppWebViewManager.keepAliveWebViews.put("A", taken);
      taken.dispose(); // Presenter detach, not an explicit keep-alive exit.
    }
    InOrder visibility = inOrder(nativeView);
    visibility.verify(nativeView).setKeepAlwaysVisibleForChromium(false);
    visibility.verify(nativeView).setKeepAlwaysVisibleForChromium(true);
    verify(nativeView, never()).dispose();
  }
}
