package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;
import android.app.Application;
import android.os.Looper;
import android.widget.FrameLayout;
import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.webview.InAppWebViewManager;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/** Production native stop entry; Robolectric models callbacks, not Chromium execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class RuntimeActivityStopNativeTest {
  private InAppWebView view(String id) {
    InAppWebViewFlutterPlugin plugin = new InAppWebViewFlutterPlugin();
    plugin.messenger = mock(BinaryMessenger.class);
    return spy(new InAppWebView(RuntimeEnvironment.getApplication(), plugin, id,
        null, new InAppWebViewSettings(), null, null, Collections.emptyList()));
  }
  @Test public void stopUnloadsOnlyOldDocumentAndRetainsViewUntilFinalDispose() {
    InAppWebView old = view("old"), next = view("next");
    old.getSettings().setJavaScriptEnabled(true);
    next.getSettings().setJavaScriptEnabled(true);
    MethodChannel.Result reply = mock(MethodChannel.Result.class);
    old.stopRuntimeActivity(reply);
    assertFalse(old.getSettings().getJavaScriptEnabled());
    assertTrue(next.getSettings().getJavaScriptEnabled());
    assertEquals("about:blank", shadowOf(old).getLastLoadedUrl());
    assertNull(shadowOf(next).getLastLoadedUrl());
    verify(old, never()).pauseTimers();
    verify(old, never()).destroy();
    verify(reply, never()).success(any());
    old.getWebViewClient().onPageFinished(old, "about:blank");
    verify(reply).success(null);
    old.loadUrl("https://old.invalid/late");
    assertEquals("about:blank", shadowOf(old).getLastLoadedUrl());
    old.dispose(); old.dispose();
    verify(old, times(1)).destroy();
    verify(next, never()).destroy();
  }
  @Test public void timeoutFailsAndAnotherNativeAttemptCanComplete() {
    InAppWebView old = view("old");
    MethodChannel.Result first = mock(MethodChannel.Result.class);
    old.stopRuntimeActivity(first);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
    verify(first).error(eq("runtime_stop_failed"), anyString(), isNull());
    verify(first, never()).success(any());
    MethodChannel.Result retry = mock(MethodChannel.Result.class);
    old.stopRuntimeActivity(retry);
    old.getWebViewClient().onPageFinished(old, "about:blank");
    verify(retry).success(null);
    verify(old, never()).destroy();
    old.dispose();
  }
  @Test public void rendererGoneDuringStopIsHandledAndDestroysOnlyOldView() {
    for (boolean alreadyStopped : new boolean[] {false, true}) {
      InAppWebView old = view("old"), next = view("next");
      MethodChannel.Result reply = mock(MethodChannel.Result.class);
      old.stopRuntimeActivity(reply);
      if (alreadyStopped) old.getWebViewClient().onPageFinished(old, "about:blank");
      assertTrue(old.getWebViewClient().onRenderProcessGone(old,
          mock(android.webkit.RenderProcessGoneDetail.class)));
      verify(old, times(1)).destroy();
      verify(next, never()).destroy();
      verify(reply).success(null);
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
      verify(reply, never()).error(anyString(), anyString(), any());
      old.dispose();
      verify(old, times(1)).destroy();
    }
  }
  @Test public void ordinaryDisposePreservesBlankCallbackDestruction() {
    for (boolean hybrid : new boolean[] {false, true}) {
      InAppWebView old = view("ordinary");
      old.customSettings.useHybridComposition = hybrid;
      FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
      parent.addView(old);
      old.dispose();
      assertSame(parent, old.getParent());
      assertEquals("about:blank", shadowOf(old).getLastLoadedUrl());
      verify(old, never()).destroy();
      old.getWebViewClient().onPageFinished(old, "about:blank");
      verify(old, times(1)).destroy();
      old.dispose();
      verify(old, times(1)).destroy();
    }
  }
  @Test public void managerReceiptConfirmsDestructionOnlyForTerminalStoppedViews() {
    for (int stopState = 0; stopState < 3; stopState++) {
      InAppWebView old = view("manager-" + stopState);
      if (stopState > 0) {
        MethodChannel.Result stopReply = mock(MethodChannel.Result.class);
        old.stopRuntimeActivity(stopReply);
        if (stopState == 1) {
          old.getWebViewClient().onPageFinished(old, "about:blank");
          verify(stopReply).success(null);
        } else {
          shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
          verify(stopReply).error(eq("runtime_stop_failed"), anyString(), isNull());
        }
      }
      InAppWebViewFlutterPlugin plugin = new InAppWebViewFlutterPlugin();
      plugin.messenger = mock(BinaryMessenger.class);
      InAppWebViewManager manager = new InAppWebViewManager(plugin);
      FlutterWebView runtime = mock(FlutterWebView.class, CALLS_REAL_METHODS);
      runtime.webView = old;
      runtime.keepAliveId = "held";
      manager.keepAliveWebViews.put("held", runtime);
      MethodChannel.Result disposeReply = mock(MethodChannel.Result.class);
      manager.onMethodCall(new MethodCall("disposeKeepAlive",
          Collections.singletonMap("keepAliveId", "held")), disposeReply);
      verify(disposeReply).success(true);
      if (stopState == 0) {
        verify(old, never()).destroy();
        old.getWebViewClient().onPageFinished(old, "about:blank");
      }
      verify(old, times(1)).destroy();
      manager.dispose();
    }
  }
}
