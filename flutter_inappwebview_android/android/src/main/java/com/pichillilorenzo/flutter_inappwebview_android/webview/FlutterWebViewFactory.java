package com.pichillilorenzo.flutter_inappwebview_android.webview;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebView;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebViewManager;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.FlutterWebView;

import java.util.HashMap;

import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugin.platform.PlatformViewFactory;

public class FlutterWebViewFactory extends PlatformViewFactory {
  public static final String VIEW_TYPE_ID = "com.pichillilorenzo/flutter_inappwebview";
  private final InAppWebViewFlutterPlugin plugin;

  public FlutterWebViewFactory(final InAppWebViewFlutterPlugin plugin) {
    super(StandardMessageCodec.INSTANCE);
    this.plugin = plugin;
  }

  @Override
  public PlatformView create(Context context, int id, Object args) {
    HashMap<String, Object> params = (HashMap<String, Object>) args;
    FlutterWebView flutterWebView = null;
    Object viewId = id;

    String keepAliveId = (String) params.get("keepAliveId");
    String headlessWebViewId = (String) params.get("headlessWebViewId");
    boolean attachOnly = Boolean.TRUE.equals(params.get("attachOnly"));

    // Strict requests have exactly one source; never borrow a different keep-alive
    // if a requested headless owner has already gone or been taken.
    if (attachOnly) {
      FlutterWebView target = null;
      if (headlessWebViewId != null) {
        HeadlessInAppWebView headless = plugin.headlessInAppWebViewManager == null ? null :
            plugin.headlessInAppWebViewManager.webViews.get(headlessWebViewId);
        if (headless != null && (keepAliveId == null || headlessWebViewId.equals(keepAliveId))) {
          target = headless.flutterWebView;
        }
      } else if (keepAliveId != null && plugin.inAppWebViewManager != null) {
        target = plugin.inAppWebViewManager.keepAliveWebViews.get(keepAliveId);
        if (target != null && !keepAliveId.equals(target.keepAliveId)) target = null;
      }
      if (target == null || target.webView == null || target.attachOnlyClaimed) {
        return new AttachmentPlatformView(context, id, null);
      }
    }

    HeadlessInAppWebViewManager headlessInAppWebViewManager = plugin.headlessInAppWebViewManager;
    if (headlessWebViewId != null && headlessInAppWebViewManager != null) {
      HeadlessInAppWebView headlessInAppWebView = headlessInAppWebViewManager.webViews.get(headlessWebViewId);
      if (headlessInAppWebView != null) {
        flutterWebView = headlessInAppWebView.disposeAndGetFlutterWebView();
        if (flutterWebView != null) {
          flutterWebView.keepAliveId = keepAliveId;
        }
      }
    }

    InAppWebViewManager inAppWebViewManager = plugin.inAppWebViewManager;
    if (keepAliveId != null && flutterWebView == null && inAppWebViewManager != null) {
      flutterWebView = inAppWebViewManager.keepAliveWebViews.get(keepAliveId);
      if (flutterWebView != null) {
        // be sure to remove the view from the previous parent.
        View view = flutterWebView.getView();
        if (view != null) {
          ViewGroup parent = (ViewGroup) view.getParent();
          if (parent != null) {
            parent.removeView(view);
          }
        }
      }
    }

    boolean shouldMakeInitialLoad = flutterWebView == null;
    if (flutterWebView == null) {
      if (keepAliveId != null) {
        viewId = keepAliveId;
      }
      flutterWebView = new FlutterWebView(plugin, context, viewId, params);
    }

    if (keepAliveId != null && inAppWebViewManager != null) {
      inAppWebViewManager.keepAliveWebViews.put(keepAliveId, flutterWebView);
    }

    if (shouldMakeInitialLoad) {
      flutterWebView.makeInitialLoad(params);
    }
    
    return attachOnly ? new AttachmentPlatformView(context, id, flutterWebView) : flutterWebView;
  }

  /** Per-platform-view pull handshake: creation can precede the Dart listener. */
  private final class AttachmentPlatformView implements PlatformView {
    private final FlutterWebView delegate;
    private final View unavailableView;
    private final MethodChannel channel;
    private boolean disposed;

    AttachmentPlatformView(Context context, int id, FlutterWebView delegate) {
      this.delegate = delegate;
      unavailableView = delegate == null ? new View(context) : null;
      if (delegate != null) delegate.attachOnlyClaimed = true;
      channel = new MethodChannel(plugin.messenger,
          "com.pichillilorenzo/flutter_inappwebview_attach_" + id);
      channel.setMethodCallHandler((call, result) -> {
        if ("isAttached".equals(call.method)) {
          result.success(!disposed && delegate != null && delegate.webView != null);
        } else {
          result.notImplemented();
        }
      });
    }

    @Override public View getView() {
      return delegate == null ? unavailableView : delegate.getView();
    }

    @Override public void onFlutterViewAttached(View flutterView) {
      if (delegate != null) delegate.onFlutterViewAttached(flutterView);
    }

    @Override public void onFlutterViewDetached() {
      if (delegate != null) delegate.onFlutterViewDetached();
    }

    @Override public void onInputConnectionLocked() {
      if (delegate != null) delegate.onInputConnectionLocked();
    }

    @Override public void onInputConnectionUnlocked() {
      if (delegate != null) delegate.onInputConnectionUnlocked();
    }

    @Override public void dispose() {
      if (disposed) return;
      disposed = true;
      channel.setMethodCallHandler(null);
      if (delegate != null) {
        delegate.attachOnlyClaimed = false;
        delegate.dispose();
      }
    }
  }
}
