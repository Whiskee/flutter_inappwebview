package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;
import android.app.Application;
import android.os.Looper;
import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
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
}
