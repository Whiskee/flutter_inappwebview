package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.WebViewStartupCoordinator;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebView;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebViewManager;
import com.pichillilorenzo.flutter_inappwebview_android.webview.FlutterWebViewFactory;
import com.pichillilorenzo.flutter_inappwebview_android.webview.InAppWebViewManager;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.platform.PlatformView;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Covers the release side of the headless takeover: which presentation state a
 * retained runtime is allowed to overwrite, and which view tree it goes back to.
 */
public class FlutterWebViewRestoreTest {

  private InAppWebViewFlutterPlugin plugin() {
    InAppWebViewFlutterPlugin plugin = new InAppWebViewFlutterPlugin();
    plugin.messenger = mock(BinaryMessenger.class);
    plugin.headlessInAppWebViewManager = new HeadlessInAppWebViewManager(plugin);
    plugin.inAppWebViewManager = new InAppWebViewManager(plugin);
    return plugin;
  }

  /** Makes {@link FlutterWebView#headlessHostView} resolve to {@code host}. */
  private void hostActivity(InAppWebViewFlutterPlugin plugin, ViewGroup host) {
    Activity activity = mock(Activity.class);
    ViewGroup contentView = mock(ViewGroup.class);
    when(activity.findViewById(android.R.id.content)).thenReturn(contentView);
    when(contentView.getChildAt(0)).thenReturn(host);
    plugin.activity = activity;
  }

  private FlutterWebView runtime(InAppWebViewFlutterPlugin plugin, InAppWebView nativeView) {
    FlutterWebView runtime = mock(FlutterWebView.class, CALLS_REAL_METHODS);
    runtime.webView = nativeView;
    runtime.plugin = plugin;
    return runtime;
  }

  // ---------------------------------------------------------------- P2-2 ----

  @Test public void releaseAfterAnActivitySwapRehostsInTheCurrentActivity() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    // The headless runtime started while Activity A was up, so the first
    // presentation snapshots A's view tree as its parent.
    ViewGroup oldHost = mock(ViewGroup.class);
    when(oldHost.indexOfChild(nativeView)).thenReturn(0);
    when(oldHost.isAttachedToWindow()).thenReturn(true);
    hostActivity(plugin, oldHost);
    when(nativeView.getParent()).thenReturn(oldHost);
    when(nativeView.getLayoutParams()).thenReturn(new FrameLayout.LayoutParams(1, 1));
    when(nativeView.getAlpha()).thenReturn(0f);
    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    // The user leaves and comes back: Activity B hosts the Flutter view now,
    // and the presenter has already detached the WebView. The old tree can
    // still report itself attached, so identity is what rules it out.
    ViewGroup newHost = mock(ViewGroup.class);
    when(newHost.isAttachedToWindow()).thenReturn(true);
    hostActivity(plugin, newHost);
    when(nativeView.getParent()).thenReturn(null);

    runtime.dispose();

    verify(newHost).addView(same(nativeView), eq(0), any());
    verify(oldHost, never()).addView(any(View.class), anyInt(), any());
  }

  @Test public void releaseIntoADetachedParentIsRefusedWhenNoActivityIsUp() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    ViewGroup oldHost = mock(ViewGroup.class);
    when(oldHost.isAttachedToWindow()).thenReturn(true);
    when(oldHost.indexOfChild(nativeView)).thenReturn(0);
    hostActivity(plugin, oldHost);
    when(nativeView.getParent()).thenReturn(oldHost);
    when(nativeView.getLayoutParams()).thenReturn(new FrameLayout.LayoutParams(1, 1));
    when(nativeView.getAlpha()).thenReturn(0f);
    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    // The Activity went away entirely while the runtime was presented, so
    // there is no current host to compare against — only the snapshot's own
    // window attachment can tell that it is dead.
    plugin.activity = null;
    when(oldHost.isAttachedToWindow()).thenReturn(false);
    when(nativeView.getParent()).thenReturn(null);

    runtime.dispose();

    verify(oldHost, never()).addView(any(View.class), anyInt(), any());
    // Still returned to background execution, just without a window.
    verify(nativeView).setKeepAlwaysVisibleForChromium(true);
  }

  @Test public void aStillParentedRuntimeIsRelocatedOutOfTheReplacedActivity() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    ViewGroup oldHost = mock(ViewGroup.class);
    when(oldHost.isAttachedToWindow()).thenReturn(true);
    when(oldHost.indexOfChild(nativeView)).thenReturn(0);
    hostActivity(plugin, oldHost);
    when(nativeView.getParent()).thenReturn(oldHost);
    when(nativeView.getLayoutParams()).thenReturn(new FrameLayout.LayoutParams(1, 1));
    when(nativeView.getAlpha()).thenReturn(0f);
    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    ViewGroup newHost = mock(ViewGroup.class);
    when(newHost.isAttachedToWindow()).thenReturn(true);
    hostActivity(plugin, newHost);

    // The presenter has not detached the runtime yet, so it is still sitting
    // in the replaced Activity's tree. "Already in the target" must be decided
    // against the Activity that is up now, not against the dead snapshot —
    // otherwise the release silently leaves it in the old hierarchy.
    try (MockedStatic<WebViewStartupCoordinator> scheduler =
             mockStatic(WebViewStartupCoordinator.class)) {
      runtime.dispose();
      ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
      scheduler.verify(() -> WebViewStartupCoordinator.postOnMain(retry.capture()));
      when(nativeView.getParent()).thenReturn(null); // presenter detach lands
      retry.getValue().run();
    }

    verify(newHost).addView(same(nativeView), eq(0), any());
    verify(oldHost, never()).addView(any(View.class), anyInt(), any());
  }

  @Test public void releaseWithinTheSameActivityRestoresTheSnapshottedSlot() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    ViewGroup host = mock(ViewGroup.class);
    when(host.isAttachedToWindow()).thenReturn(true);
    when(host.indexOfChild(nativeView)).thenReturn(3);
    when(host.getChildCount()).thenReturn(5);
    hostActivity(plugin, host);
    when(nativeView.getParent()).thenReturn(host);
    when(nativeView.getLayoutParams()).thenReturn(new FrameLayout.LayoutParams(1, 1));
    when(nativeView.getAlpha()).thenReturn(0f);
    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    when(nativeView.getParent()).thenReturn(null);
    runtime.dispose();

    // Same Activity: the runtime keeps its original position, not index 0.
    verify(host).addView(same(nativeView), eq(3), any());
  }

  @Test public void sameHostReleaseStabilizesHeadlessStateAfterPresenterCleanup() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    ViewGroup host = mock(ViewGroup.class);
    when(host.isAttachedToWindow()).thenReturn(true);
    when(host.indexOfChild(nativeView)).thenReturn(0);
    hostActivity(plugin, host);
    when(nativeView.getParent()).thenReturn(host);

    FrameLayout.LayoutParams headlessLayout = new FrameLayout.LayoutParams(1, 1);
    AtomicReference<ViewGroup.LayoutParams> actualLayout =
        new AtomicReference<>(headlessLayout);
    AtomicReference<Float> actualAlpha = new AtomicReference<>(0f);
    when(nativeView.getLayoutParams()).thenAnswer(ignored -> actualLayout.get());
    doAnswer(invocation -> {
      actualLayout.set(invocation.getArgument(0));
      return null;
    }).when(nativeView).setLayoutParams(any());
    when(nativeView.getAlpha()).thenAnswer(ignored -> actualAlpha.get());
    doAnswer(invocation -> {
      actualAlpha.set(invocation.getArgument(0));
      return null;
    }).when(nativeView).setAlpha(anyFloat());

    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    try (MockedStatic<WebViewStartupCoordinator> scheduler =
             mockStatic(WebViewStartupCoordinator.class)) {
      runtime.dispose();

      ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);
      scheduler.verify(() -> WebViewStartupCoordinator.postOnMain(finalizer.capture()));

      // PlatformViewsController finishes its own teardown after dispose() and
      // can write the presenter's full-screen state back over the synchronous
      // headless restore. The queued finalizer owns the actual final state.
      FrameLayout.LayoutParams presenterLayout = new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
      actualLayout.set(presenterLayout);
      actualAlpha.set(1f);

      finalizer.getValue().run();
    }

    assertSame(headlessLayout, actualLayout.get());
    assertEquals(0f, actualAlpha.get(), 0f);
  }

  @Test public void aNewPresentationInvalidatesTheQueuedBackgroundFinalizer() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    ViewGroup host = mock(ViewGroup.class);
    when(host.isAttachedToWindow()).thenReturn(true);
    when(host.indexOfChild(nativeView)).thenReturn(0);
    hostActivity(plugin, host);
    when(nativeView.getParent()).thenReturn(host);

    FrameLayout.LayoutParams headlessLayout = new FrameLayout.LayoutParams(1, 1);
    AtomicReference<ViewGroup.LayoutParams> actualLayout =
        new AtomicReference<>(headlessLayout);
    AtomicReference<Float> actualAlpha = new AtomicReference<>(0f);
    when(nativeView.getLayoutParams()).thenAnswer(ignored -> actualLayout.get());
    doAnswer(invocation -> {
      actualLayout.set(invocation.getArgument(0));
      return null;
    }).when(nativeView).setLayoutParams(any());
    when(nativeView.getAlpha()).thenAnswer(ignored -> actualAlpha.get());
    doAnswer(invocation -> {
      actualAlpha.set(invocation.getArgument(0));
      return null;
    }).when(nativeView).setAlpha(anyFloat());

    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    try (MockedStatic<WebViewStartupCoordinator> scheduler =
             mockStatic(WebViewStartupCoordinator.class)) {
      runtime.dispose();
      ArgumentCaptor<Runnable> finalizer = ArgumentCaptor.forClass(Runnable.class);
      scheduler.verify(() -> WebViewStartupCoordinator.postOnMain(finalizer.capture()));

      // A new route begins presenting the retained runtime before its native
      // AttachmentPlatformView has finished claiming it. The presentation
      // generation must already make the old release task stale.
      runtime.prepareForPresentation();
      ViewGroup.LayoutParams newPresentationLayout = actualLayout.get();
      assertNotSame(headlessLayout, newPresentationLayout);
      assertEquals(1f, actualAlpha.get(), 0f);

      finalizer.getValue().run();

      assertSame(newPresentationLayout, actualLayout.get());
      assertEquals(1f, actualAlpha.get(), 0f);
    }
  }

  @Test public void aWindowlessWakeUpStillHostsInTheActivityThatShowedUp() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "A";

    // Background CDM / PendingIntent wake-up: no Activity, so no parent to snapshot.
    plugin.activity = null;
    when(nativeView.getParent()).thenReturn(null);
    when(nativeView.getLayoutParams()).thenReturn(null);
    runtime.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
    runtime.prepareForPresentation();

    ViewGroup host = mock(ViewGroup.class);
    when(host.isAttachedToWindow()).thenReturn(true);
    hostActivity(plugin, host);
    runtime.dispose();

    verify(host).addView(same(nativeView), eq(0), any());
  }

  // ---------------------------------------------------------------- P2-3 ----

  @Test public void ordinaryKeepAliveRemountKeepsTheCallersPresentationState() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    runtime.keepAliveId = "K";
    plugin.inAppWebViewManager.keepAliveWebViews.put("K", runtime);

    HashMap<String, Object> params = new HashMap<>();
    params.put("keepAliveId", "K");
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class)) {
      new FlutterWebViewFactory(plugin).create(mock(Context.class), 20, params);
      assertEquals(0, allocated.constructed().size()); // the retained runtime was reused
    }

    // This runtime never ran headless, so there is no hidden geometry to undo:
    // whatever visibility/alpha/layout the caller set has to survive the remount.
    verify(nativeView, never()).setAlpha(anyFloat());
    verify(nativeView, never()).setVisibility(anyInt());
    verify(nativeView, never()).setLayoutParams(any());
    verify(nativeView, never()).setKeepAlwaysVisibleForChromium(anyBoolean());
  }

  @Test public void headlessReleasePresentationStillForcesForegroundGeometry() {
    InAppWebViewFlutterPlugin plugin = plugin();
    InAppWebView nativeView = mock(InAppWebView.class);
    FlutterWebView runtime = runtime(plugin, nativeView);
    FrameLayout.LayoutParams headlessLayout = new FrameLayout.LayoutParams(1, 1);
    when(nativeView.getLayoutParams()).thenReturn(headlessLayout);
    when(nativeView.getAlpha()).thenReturn(0f);
    HeadlessInAppWebView headless = new HeadlessInAppWebView(plugin, "A", runtime);
    plugin.headlessInAppWebViewManager.webViews.put("A", headless);

    HashMap<String, Object> params = new HashMap<>();
    params.put("headlessWebViewId", "A");
    params.put("keepAliveId", "A");
    try (MockedConstruction<FlutterWebView> allocated = mockConstruction(FlutterWebView.class)) {
      new FlutterWebViewFactory(plugin).create(mock(Context.class), 21, params);
      assertEquals(0, allocated.constructed().size());
    }

    // A runtime coming back from 1×1 alpha=0 headless execution must be made
    // presentable, and its anti-throttling hack disarmed for the presentation.
    verify(nativeView).setAlpha(1f);
    verify(nativeView).setVisibility(View.VISIBLE);
    verify(nativeView).setKeepAlwaysVisibleForChromium(false);
    // Geometry is asserted by identity: the mockable android.jar used by unit
    // tests drops constructor bodies, so LayoutParams.width/height never hold
    // the value they were built with. What matters is that the hidden 1×1
    // snapshot is replaced rather than carried into the presentation.
    ArgumentCaptor<ViewGroup.LayoutParams> presented =
        ArgumentCaptor.forClass(ViewGroup.LayoutParams.class);
    verify(nativeView).setLayoutParams(presented.capture());
    assertNotSame(headlessLayout, presented.getValue());
  }
}
