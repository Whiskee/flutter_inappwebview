package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.find_interaction.FindInteractionController;
import com.pichillilorenzo.flutter_inappwebview_android.pull_to_refresh.PullToRefreshLayout;
import com.pichillilorenzo.flutter_inappwebview_android.pull_to_refresh.PullToRefreshSettings;
import com.pichillilorenzo.flutter_inappwebview_android.webview.PlatformWebView;
import com.pichillilorenzo.flutter_inappwebview_android.types.URLRequest;
import com.pichillilorenzo.flutter_inappwebview_android.types.UserScript;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlutterWebView implements PlatformWebView {

  static final String LOG_TAG = "IAWFlutterWebView";

  @Nullable
  public InAppWebView webView;
  @Nullable
  public PullToRefreshLayout pullToRefreshLayout;
  @Nullable
  public String keepAliveId;
  // One strict presenter may claim this exact live instance until detach.
  public boolean attachOnlyClaimed;
  // Set by the headless takeover, which disarms the always-visible-to-Chromium
  // hack for the duration of the presentation.
  public boolean restoreKeepAlwaysVisibleForChromiumOnRelease;
  @Nullable
  private ViewGroup.LayoutParams backgroundLayoutParams;
  @Nullable
  private Float backgroundAlpha;

  public FlutterWebView(final InAppWebViewFlutterPlugin plugin, final Context context, Object id,
                        HashMap<String, Object> params) {
    DisplayListenerProxy displayListenerProxy = new DisplayListenerProxy();
    DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    displayListenerProxy.onPreWebViewInitialization(displayManager);

    keepAliveId = (String) params.get("keepAliveId");
    
    Map<String, Object> initialSettings = (Map<String, Object>) params.get("initialSettings");
    Map<String, Object> contextMenu = (Map<String, Object>) params.get("contextMenu");
    Integer windowId = (Integer) params.get("windowId");
    List<Map<String, Object>> initialUserScripts = (List<Map<String, Object>>) params.get("initialUserScripts");
    Map<String, Object> pullToRefreshInitialSettings = (Map<String, Object>) params.get("pullToRefreshSettings");

    InAppWebViewSettings customSettings = new InAppWebViewSettings();
    customSettings.parse(initialSettings);

    List<UserScript> userScripts = new ArrayList<>();
    if (initialUserScripts != null) {
      for (Map<String, Object> initialUserScript : initialUserScripts) {
        userScripts.add(UserScript.fromMap(initialUserScript));
      }
    }

    webView = new InAppWebView(context, plugin, id, windowId, customSettings, contextMenu, 
            customSettings.useHybridComposition ? null : plugin.flutterView, userScripts);
    displayListenerProxy.onPostWebViewInitialization(displayManager);

    // set MATCH_PARENT layout params to the WebView, otherwise it won't take all the available space!
    webView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    PullToRefreshSettings pullToRefreshSettings = new PullToRefreshSettings();
    pullToRefreshSettings.parse(pullToRefreshInitialSettings);
    pullToRefreshLayout = new PullToRefreshLayout(context, plugin, id, pullToRefreshSettings);
    pullToRefreshLayout.addView(webView);
    pullToRefreshLayout.prepare();

    FindInteractionController findInteractionController = new FindInteractionController(webView, plugin, id, null);
    webView.findInteractionController = findInteractionController;
    findInteractionController.prepare();

    webView.prepare();
  }

  @Override
  public View getView() {
    return pullToRefreshLayout != null ? pullToRefreshLayout : webView;
  }

  /** Restores normal foreground semantics before any retained presentation. */
  public void prepareForPresentation() {
    View view = getView();
    if (view != null) {
      if (restoreKeepAlwaysVisibleForChromiumOnRelease && backgroundLayoutParams == null) {
        backgroundLayoutParams = view.getLayoutParams();
        backgroundAlpha = view.getAlpha();
      }
      view.setLayoutParams(new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT));
      view.setVisibility(View.VISIBLE);
      view.setAlpha(1f);
    }
    if (restoreKeepAlwaysVisibleForChromiumOnRelease && webView != null) {
      webView.setKeepAlwaysVisibleForChromium(false);
    }
  }

  /** Restores the exact hidden headless geometry after a presenter releases it. */
  private void prepareForBackgroundRuntime() {
    View view = getView();
    if (view != null) {
      if (backgroundLayoutParams != null) {
        view.setLayoutParams(backgroundLayoutParams);
      }
      view.setVisibility(View.VISIBLE);
      view.setAlpha(backgroundAlpha != null ? backgroundAlpha : 0f);
    }
    backgroundLayoutParams = null;
    backgroundAlpha = null;
    if (webView != null) {
      webView.setKeepAlwaysVisibleForChromium(true);
    }
  }

  @SuppressLint("RestrictedApi")
  public void makeInitialLoad(HashMap<String, Object> params) {
    if (webView == null) {
      return;
    }

    Integer windowId = (Integer) params.get("windowId");
    final Map<String, Object> initialUrlRequest = (Map<String, Object>) params.get("initialUrlRequest");
    final String initialFile = (String) params.get("initialFile");
    final Map<String, String> initialData = (Map<String, String>) params.get("initialData");

    if (windowId != null) {
      webView.completeWindowCreation();
    } else {
      // The first navigation must also wait for asynchronous registration retries.
      // This barrier does not require a Headless WebView to be attached to a window.
      final InAppWebView expectedWebView = webView;
      expectedWebView.runWhenInitialJavaScriptBridgeReadyForNavigation(new InAppWebView.InitialNavigationCallback() {
        @Override
        public void onSuccess() {
          if (webView != expectedWebView) {
            return;
          }
          if (initialFile != null) {
            try {
              expectedWebView.loadFile(initialFile);
            } catch (IOException e) {
              Log.e(LOG_TAG, initialFile + " asset file cannot be found!", e);
            }
          }
          else if (initialData != null) {
            String data = initialData.get("data");
            String mimeType = initialData.get("mimeType");
            String encoding = initialData.get("encoding");
            String baseUrl = initialData.get("baseUrl");
            String historyUrl = initialData.get("historyUrl");
            expectedWebView.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
          }
          else if (initialUrlRequest != null) {
            URLRequest urlRequest = URLRequest.fromMap(initialUrlRequest);
            if (urlRequest != null) {
              expectedWebView.loadUrl(urlRequest);
            }
          }
        }

        @Override
        public void onError(@NonNull Throwable error) {
          Log.e(LOG_TAG, "Initial load cancelled: JavaScript bridge registration failed", error);
        }
      });
    }
  }

  @Override
  public void dispose() {
    if (keepAliveId == null && webView != null) {
      webView.dispose();
      webView = null;

      if (pullToRefreshLayout != null) {
        pullToRefreshLayout.dispose();
        pullToRefreshLayout = null;
      }
      return;
    }
    // Surviving the presenter means going back to running with no window, so a
    // WebView taken over from a headless runtime has to get its anti-throttling
    // hack back; the takeover only disarmed it for the presentation.
    if (restoreKeepAlwaysVisibleForChromiumOnRelease && webView != null) {
      prepareForBackgroundRuntime();
    }
  }

  @Override
  public void onInputConnectionLocked() {
    if (webView != null && webView.inAppBrowserDelegate == null && !webView.customSettings.useHybridComposition)
      webView.lockInputConnection();
  }

  @Override
  public void onInputConnectionUnlocked() {
    if (webView != null && webView.inAppBrowserDelegate == null && !webView.customSettings.useHybridComposition)
      webView.unlockInputConnection();
  }

  @Override
  public void onFlutterViewAttached(@NonNull View flutterView) {
    if (webView != null && !webView.customSettings.useHybridComposition) {
      webView.setContainerView(flutterView);
    }
  }

  @Override
  public void onFlutterViewDetached() {
    if (webView != null && !webView.customSettings.useHybridComposition) {
      webView.setContainerView(null);
    }
  }
}
