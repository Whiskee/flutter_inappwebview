package com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.Util;
import com.pichillilorenzo.flutter_inappwebview_android.types.Disposable;
import com.pichillilorenzo.flutter_inappwebview_android.types.Size2D;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.FlutterWebView;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.InputAwareWebView;

import java.util.Map;

import io.flutter.plugin.common.MethodChannel;

public class HeadlessInAppWebView implements Disposable {
  protected static final String LOG_TAG = "HeadlessInAppWebView";
  public static final String METHOD_CHANNEL_NAME_PREFIX = "com.pichillilorenzo/flutter_headless_inappwebview_";
  
  @NonNull
  public final String id;
  @Nullable
  public HeadlessWebViewChannelDelegate channelDelegate;
  @Nullable
  public FlutterWebView flutterWebView;
  @Nullable
  public InAppWebViewFlutterPlugin plugin;

  public HeadlessInAppWebView(@NonNull final InAppWebViewFlutterPlugin plugin, @NonNull String id, @NonNull FlutterWebView flutterWebView) {
    this.id = id;
    this.plugin = plugin;
    this.flutterWebView = flutterWebView;
    final MethodChannel channel = new MethodChannel(plugin.messenger, METHOD_CHANNEL_NAME_PREFIX + id);
    this.channelDelegate = new HeadlessWebViewChannelDelegate(this, channel);
  }

  public void onWebViewCreated() {
    if (channelDelegate != null) {
      channelDelegate.onWebViewCreated();
    }
  }

  public void prepare(Map<String, Object> params) {
    if (flutterWebView != null) {
      View view = flutterWebView.getView();
      if (view != null) {
        final Map<String, Object> initialSize = (Map<String, Object>) params.get("initialSize");
        Size2D size = Size2D.fromMap(initialSize);
        if (size == null) {
          // CHANGED FROM UPSTREAM: default to 1×1 instead of fullscreen (-1, -1).
          //
          // We now keep the headless WebView in {@link View#VISIBLE} state so
          // Chromium considers the page "visible" and keeps rAF / timers running
          // in background (see {@code keepAlwaysVisibleForChromium} below).
          // A fullscreen visible WebView at alpha=0 would still trigger a
          // full-screen GPU raster every frame; 1×1 minimizes that cost.
          // Callers that need a larger headless surface (e.g. for screenshots)
          // can still pass an explicit initialSize.
          size = new Size2D(1, 1);
        }
        setSize(size);
        // CHANGED FROM UPSTREAM: VISIBLE + alpha(0) instead of INVISIBLE.
        //
        // {@code View.INVISIBLE} makes Android's window-visibility dispatch
        // propagate "hidden" to the WebView, which Chromium uses to throttle
        // rAF to 0 fps and timers to 1 Hz. By keeping the View VISIBLE (and
        // pairing with the {@code keepAlwaysVisibleForChromium} hook below)
        // Chromium sees the page as visible regardless of host Activity
        // lifecycle. alpha=0 keeps the WebView visually invisible.
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
      }
    }
    if (plugin != null && plugin.activity != null) {
      // Add the headless WebView to the view hierarchy.
      // This way is also possible to take screenshots.
      ViewGroup contentView = (ViewGroup) plugin.activity.findViewById(android.R.id.content);
      if (contentView != null) {
        ViewGroup mainView = (ViewGroup) (contentView).getChildAt(0);
        if (mainView != null && flutterWebView != null) {
          View view = flutterWebView.getView();
          if (view != null) {
            mainView.addView(view, 0);
          }
        }
      }
    }
    // CHANGED FROM UPSTREAM: enable the always-visible-to-Chromium hack.
    //
    // This is armed *after* the optional addView() above, because
    // setKeepAlwaysVisibleForChromium also calls
    // super.onWindowVisibilityChanged(VISIBLE) once, and that one-shot signal
    // only reaches Chromium when the View is already attached to a window.
    //
    // It must NOT be nested inside the `plugin.activity != null` branch: a
    // headless WebView started from a background CDM / PendingIntent wake-up
    // has no Activity at all, which is exactly the case this anti-throttling
    // hack exists for. The flag itself has no Activity dependency — it only
    // gates this class' visibility callbacks and getters.
    //
    // IMPORTANT: flutterWebView.getView() returns the pullToRefreshLayout
    // wrapper when one exists — NOT the InAppWebView itself. Use
    // flutterWebView.webView directly so the {@code instanceof
    // InputAwareWebView} check actually succeeds. (Earlier revisions used the
    // wrapper here and silently no-op'd.)
    if (flutterWebView != null && flutterWebView.webView instanceof InputAwareWebView) {
      ((InputAwareWebView) flutterWebView.webView)
          .setKeepAlwaysVisibleForChromium(true);
    }
  }
  
  public void setSize(@NonNull Size2D size) {
    if (flutterWebView != null && flutterWebView.webView != null) {
      View view = flutterWebView.getView();
      if (view != null) {
        float scale = Util.getPixelDensity(view.getContext());
        Size2D fullscreenSize = Util.getFullscreenSize(view.getContext());
        int width = (int) (size.getWidth() == -1 ? fullscreenSize.getWidth() : (size.getWidth() * scale));
        int height = (int) (size.getWidth() == -1 ? fullscreenSize.getHeight() : (size.getHeight() * scale));
        view.setLayoutParams(new FrameLayout.LayoutParams(width, height));
      }
    }
  }

  @Nullable
  public Size2D getSize() {
    if (flutterWebView != null && flutterWebView.webView != null) {
      View view = flutterWebView.getView();
      if (view != null) {
        float scale = Util.getPixelDensity(view.getContext());
        Size2D fullscreenSize = Util.getFullscreenSize(view.getContext());
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        return new Size2D(
                fullscreenSize.getWidth() == layoutParams.width ? layoutParams.width : (layoutParams.width / scale),
                fullscreenSize.getHeight() == layoutParams.height ? layoutParams.height : (layoutParams.height / scale)
        );
      }
    }
    return null;
  }

  @Nullable
  public FlutterWebView disposeAndGetFlutterWebView() {
    FlutterWebView newFlutterWebView = flutterWebView;
    if (flutterWebView != null) {
      View view = flutterWebView.getView();
      if (view != null) {
        // restore WebView layout params and visibility
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.setVisibility(View.VISIBLE);
        // CHANGED FROM UPSTREAM: also restore alpha=1 (we set it to 0 in
        // prepare() to keep the headless view visually hidden) and disable the
        // always-visible Chromium hack. The takeover transfers this WebView
        // to the foreground InAppWebView widget where standard visibility
        // behavior is expected:
        // - alpha=1 so the user actually sees the rendered page;
        // - keepAlwaysVisibleForChromium=false so Chromium can react to real
        //   off-screen / background state again (pause rendering when the
        //   plugin window is actually hidden by user navigation).
        view.setAlpha(1f);
        // Same wrapper-pitfall as in prepare(): operate on flutterWebView.webView
        // (the InAppWebView itself) instead of the pullToRefreshLayout wrapper
        // returned by getView().
        if (flutterWebView.webView instanceof InputAwareWebView) {
          ((InputAwareWebView) flutterWebView.webView)
              .setKeepAlwaysVisibleForChromium(false);
        }
        // The disarm above is only correct while a presenter is showing this
        // WebView. A retained (keep-alive) WebView that outlives its presenter
        // is back to running without any window, so FlutterWebView.dispose()
        // must re-arm it — otherwise the very first foreground presentation
        // permanently returns the background runtime to Chromium throttling.
        flutterWebView.restoreKeepAlwaysVisibleForChromiumOnRelease = true;
        // remove from parent
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
          parent.removeView(view);
        }
      }
      // set to null to avoid to be disposed before calling "dispose()"
      flutterWebView = null;
      dispose();
    }
    return newFlutterWebView;
  }

  public void dispose() {
    if (channelDelegate != null) {
      channelDelegate.dispose();
      channelDelegate = null;
    }
    if (plugin != null) {
      HeadlessInAppWebViewManager headlessInAppWebViewManager = plugin.headlessInAppWebViewManager;
      if (headlessInAppWebViewManager != null && headlessInAppWebViewManager.webViews.containsKey(id)) {
        headlessInAppWebViewManager.webViews.put(id, null);
      }
      Activity activity =  plugin.activity;
      if (activity != null) {
        ViewGroup contentView = plugin.activity.findViewById(android.R.id.content);
        if (contentView != null) {
          ViewGroup mainView = (ViewGroup) (contentView).getChildAt(0);
          if (mainView != null && flutterWebView != null) {
            View view = flutterWebView.getView();
            if (view != null) {
              mainView.removeView(flutterWebView.getView());
            }
          }
        }
      }
    }
    if (flutterWebView != null) {
      flutterWebView.dispose();
    }
    flutterWebView = null;
    plugin = null;
  }
}
