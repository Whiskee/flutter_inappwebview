package com.pichillilorenzo.flutter_inappwebview_android.webview;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebView;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebViewManager;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.FlutterWebView;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.InAppWebView;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.StandardMethodCodec;
import io.flutter.plugin.platform.PlatformView;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import java.util.HashMap;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FlutterWebViewFactoryTest {
  private InAppWebViewFlutterPlugin plugin() {
    InAppWebViewFlutterPlugin plugin = new InAppWebViewFlutterPlugin();
    plugin.messenger = mock(BinaryMessenger.class);
    plugin.headlessInAppWebViewManager = new HeadlessInAppWebViewManager(plugin);
    plugin.inAppWebViewManager = new InAppWebViewManager(plugin);
    return plugin;
  }

  private boolean attached(InAppWebViewFlutterPlugin plugin, int id) {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> handler =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    verify(plugin.messenger).setMessageHandler(
        eq("com.pichillilorenzo/flutter_inappwebview_attach_" + id), handler.capture());
    Object[] result = {null};
    ByteBuffer request = StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall("isAttached", null));
    request.flip();
    handler.getValue().onMessage(request, reply -> {
      reply.flip();
      result[0] = StandardMethodCodec.INSTANCE.decodeEnvelope(reply);
    });
    return Boolean.TRUE.equals(result[0]);
  }

  @Test public void exactTransferDetachesReattachesAndExplicitlyExitsSameNativeView() {
    InAppWebViewFlutterPlugin plugin = plugin();
    FlutterWebView runtime = mock(FlutterWebView.class, CALLS_REAL_METHODS);
    InAppWebView nativeView = mock(InAppWebView.class);
    FrameLayout.LayoutParams headlessLayout = new FrameLayout.LayoutParams(3, 5);
    when(nativeView.getLayoutParams()).thenReturn(headlessLayout);
    when(nativeView.getAlpha()).thenReturn(0f);
    runtime.webView = nativeView;
    HeadlessInAppWebView headless = new HeadlessInAppWebView(plugin, "A", runtime);
    plugin.headlessInAppWebViewManager.webViews.put("A", headless);
    HashMap<String, Object> params = new HashMap<>();
    params.put("attachOnly", true);
    params.put("headlessWebViewId", "A");
    params.put("keepAliveId", "A");
    FlutterWebViewFactory factory = new FlutterWebViewFactory(plugin);
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class);
         MockedConstruction<View> views = mockConstruction(View.class)) {
      PlatformView first = factory.create(mock(Context.class), 10, params);
      assertTrue(attached(plugin, 10));
      assertSame(nativeView, first.getView());
      assertNull(headless.flutterWebView);
      assertSame(runtime, plugin.inAppWebViewManager.keepAliveWebViews.get("A"));
      params.remove("headlessWebViewId");
      factory.create(mock(Context.class), 11, params);
      assertFalse(attached(plugin, 11)); // Already claimed, not a second presenter.
      first.dispose();
      verify(nativeView, never()).dispose();
      verify(nativeView).setLayoutParams(same(headlessLayout));
      verify(nativeView).setAlpha(0f);
      PlatformView second = factory.create(mock(Context.class), 12, params);
      assertTrue(attached(plugin, 12));
      assertSame(nativeView, second.getView());
      // The first presenter re-arms anti-throttling when it releases the
      // retained runtime. A later presenter must disarm it again.
      verify(nativeView, times(2)).setKeepAlwaysVisibleForChromium(false);
      verify(runtime, never()).makeInitialLoad(any());
      assertEquals(0, allocated.constructed().size());
      plugin.inAppWebViewManager.disposeKeepAlive("A");
      verify(nativeView).dispose();
      assertNull(runtime.webView);
      second.dispose();
      assertNull(plugin.inAppWebViewManager.keepAliveWebViews.get("A"));
    }
  }

  @Test public void revokedAfterSubmissionIsUnavailableAtNativeCreation() {
    InAppWebViewFlutterPlugin plugin = plugin();
    FlutterWebView runtime = mock(FlutterWebView.class, CALLS_REAL_METHODS);
    runtime.webView = mock(InAppWebView.class);
    HeadlessInAppWebView headless = new HeadlessInAppWebView(plugin, "A", runtime);
    plugin.headlessInAppWebViewManager.webViews.put("A", headless);
    HashMap<String, Object> params = new HashMap<>();
    params.put("attachOnly", true);
    params.put("headlessWebViewId", "A");
    params.put("keepAliveId", "A");
    headless.dispose(); // Actual resource-owner boundary, before factory consumes params.
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class);
         MockedConstruction<View> views = mockConstruction(View.class)) {
      new FlutterWebViewFactory(plugin).create(mock(Context.class), 13, params);
      assertFalse(attached(plugin, 13));
      assertEquals(0, allocated.constructed().size());
    }
  }

  @Test public void strictMissingTargetDoesNotAllocateOrLoad() {
    InAppWebViewFlutterPlugin plugin = plugin();
    HashMap<String, Object> params = new HashMap<>();
    params.put("attachOnly", true);
    params.put("headlessWebViewId", "retired-A");
    params.put("keepAliveId", "retired-A");
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class);
         MockedConstruction<View> views = mockConstruction(View.class)) {
      new FlutterWebViewFactory(plugin).create(mock(Context.class), 7, params);
      assertEquals(0, allocated.constructed().size());
      assertFalse(plugin.inAppWebViewManager.keepAliveWebViews.containsKey("retired-A"));
    }
  }

  @Test public void ordinaryMissingTargetStillAllocatesAndLoads() {
    InAppWebViewFlutterPlugin plugin = plugin();
    HashMap<String, Object> params = new HashMap<>();
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class)) {
      new FlutterWebViewFactory(plugin).create(mock(Context.class), 8, params);
      assertEquals(1, allocated.constructed().size());
      verify(allocated.constructed().get(0)).makeInitialLoad(params);
    }
  }

  @Test public void strictHeadlessWithoutRetentionKeyCannotConsumeTheLiveWebView() {
    InAppWebViewFlutterPlugin plugin = plugin();
    FlutterWebView runtime = mock(FlutterWebView.class, CALLS_REAL_METHODS);
    InAppWebView nativeView = mock(InAppWebView.class);
    runtime.webView = nativeView;
    HeadlessInAppWebView headless = new HeadlessInAppWebView(plugin, "A", runtime);
    plugin.headlessInAppWebViewManager.webViews.put("A", headless);
    HashMap<String, Object> params = new HashMap<>();
    params.put("attachOnly", true);
    params.put("headlessWebViewId", "A");
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class);
         MockedConstruction<View> views = mockConstruction(View.class)) {
      PlatformView presenter = new FlutterWebViewFactory(plugin)
          .create(mock(Context.class), 14, params);
      assertFalse(attached(plugin, 14));
      assertSame(runtime, headless.flutterWebView); // Headless owner keeps running.
      presenter.dispose();
      verify(nativeView, never()).dispose();
      assertEquals(0, allocated.constructed().size());
      assertFalse(plugin.inAppWebViewManager.keepAliveWebViews.containsKey("A"));
    }
  }

  @Test public void strictMissingHeadlessCannotBorrowAnotherKeepAliveOwner() {
    InAppWebViewFlutterPlugin plugin = plugin();
    FlutterWebView successor = mock(FlutterWebView.class);
    plugin.inAppWebViewManager.keepAliveWebViews.put("B", successor);
    HashMap<String, Object> params = new HashMap<>();
    params.put("attachOnly", true);
    params.put("headlessWebViewId", "A");
    params.put("keepAliveId", "B");
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class);
         MockedConstruction<View> views = mockConstruction(View.class)) {
      new FlutterWebViewFactory(plugin).create(mock(Context.class), 9, params);
      verifyNoInteractions(successor);
      assertSame(successor, plugin.inAppWebViewManager.keepAliveWebViews.get("B"));
      assertEquals(0, allocated.constructed().size());
    }
  }
}
