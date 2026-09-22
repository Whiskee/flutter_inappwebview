package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
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
  // The plugin is the only way to reach an Activity that attached after a
  // background (CDM / PendingIntent) wake-up created this runtime windowless.
  @Nullable
  public InAppWebViewFlutterPlugin plugin;
  // Snapshot taken by the first presentation of a retained headless runtime.
  // Tracked explicitly: a snapshot whose layout params were null must not be
  // retaken from a later presentation's MATCH_PARENT geometry.
  private boolean backgroundSnapshotTaken;
  @Nullable
  private ViewGroup.LayoutParams backgroundLayoutParams;
  @Nullable
  private Float backgroundAlpha;
  @Nullable
  private ViewGroup backgroundParent;
  private int backgroundParentIndex = -1;
  // Invalidates a queued background restore as soon as a newer presentation
  // starts, including the brief interval before AttachmentPlatformView claims it.
  private long presentationGeneration;

  public FlutterWebView(final InAppWebViewFlutterPlugin plugin, final Context context, Object id,
                        HashMap<String, Object> params) {
    this.plugin = plugin;
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

  /**
   * The Activity view the headless runtime is hosted in when one exists: the
   * first child of {@code android.R.id.content}, which is what
   * {@code HeadlessInAppWebView#prepare} attaches to. Null without an Activity.
   */
  @Nullable
  public static ViewGroup headlessHostView(@Nullable InAppWebViewFlutterPlugin plugin) {
    if (plugin == null || plugin.activity == null) {
      return null;
    }
    ViewGroup contentView = (ViewGroup) plugin.activity.findViewById(android.R.id.content);
    if (contentView == null) {
      return null;
    }
    View mainView = contentView.getChildAt(0);
    return mainView instanceof ViewGroup ? (ViewGroup) mainView : null;
  }

  /** The hidden 1×1 geometry a headless runtime runs with (see HeadlessInAppWebView#prepare). */
  @NonNull
  private static ViewGroup.LayoutParams headlessLayoutParams() {
    return new FrameLayout.LayoutParams(1, 1);
  }

  /**
   * Restores normal foreground semantics before a retained presentation.
   *
   * <p>Only a runtime released from headless execution needs this: it runs 1×1
   * at alpha 0 with the anti-throttling hack armed, none of which a presenter
   * can show. An ordinary keep-alive remount is already in whatever state its
   * caller put it in, so every action below shares the one discriminator
   * instead of some of them being unconditional — forcing MATCH_PARENT,
   * VISIBLE and alpha 1 on a plain keep-alive silently undoes the caller's own
   * setVisibility/setAlpha.
   */
  public void prepareForPresentation() {
    if (!restoreKeepAlwaysVisibleForChromiumOnRelease) {
      return;
    }
    presentationGeneration++;
    View view = getView();
    if (view != null) {
      if (!backgroundSnapshotTaken) {
        backgroundSnapshotTaken = true;
        backgroundLayoutParams = view.getLayoutParams();
        backgroundAlpha = view.getAlpha();
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
          backgroundParent = (ViewGroup) parent;
          backgroundParentIndex = backgroundParent.indexOfChild(view);
        }
      }
      view.setLayoutParams(new FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT));
      view.setVisibility(View.VISIBLE);
      view.setAlpha(1f);
    }
    if (webView != null) {
      webView.setKeepAlwaysVisibleForChromium(false);
    }
  }

  /** Restores the exact hidden headless geometry after a presenter releases it. */
  private void prepareForBackgroundRuntime() {
    View view = getView();
    if (view != null) {
      // A runtime that never had layout params (created windowless) must not
      // keep the presenter's MATCH_PARENT geometry: a full-screen alpha=0 view
      // reattached to the Activity would still swallow touches.
      final ViewGroup.LayoutParams retainedLayoutParams =
              backgroundLayoutParams != null ? backgroundLayoutParams : headlessLayoutParams();
      final float retainedAlpha = backgroundAlpha != null ? backgroundAlpha : 0f;
      final ViewGroup retainedParent = backgroundParent;
      final int retainedParentIndex = backgroundParentIndex;
      final long releaseGeneration = presentationGeneration;
      view.setLayoutParams(retainedLayoutParams);
      view.setVisibility(View.VISIBLE);
      view.setAlpha(retainedAlpha);
      restoreBackgroundAttachment(view, retainedLayoutParams);
      // PlatformViewsController completes presentation teardown after
      // PlatformView.dispose() returns. It can therefore re-apply the
      // presenter's full-screen layout after the synchronous restore above.
      // Reassert the retained runtime state once that call stack has drained.
      com.pichillilorenzo.flutter_inappwebview_android.WebViewStartupCoordinator.postOnMain(
              () -> stabilizeBackgroundRuntime(
                      view,
                      retainedLayoutParams,
                      retainedAlpha,
                      retainedParent,
                      retainedParentIndex,
                      releaseGeneration));
    } else {
      clearBackgroundSnapshot();
    }
    if (webView != null) {
      webView.setKeepAlwaysVisibleForChromium(true);
    }
  }

  private void stabilizeBackgroundRuntime(
          @NonNull View retainedView,
          @NonNull ViewGroup.LayoutParams retainedLayoutParams,
          float retainedAlpha,
          @Nullable ViewGroup retainedParent,
          int retainedParentIndex,
          long releaseGeneration
  ) {
    // A newer presentation needs the original background snapshot for its own
    // release, so its generation invalidates this task without clearing it.
    if (presentationGeneration != releaseGeneration) {
      return;
    }
    if (webView == null || getView() != retainedView) {
      clearBackgroundSnapshot();
      return;
    }
    retainedView.setLayoutParams(retainedLayoutParams);
    retainedView.setVisibility(View.VISIBLE);
    retainedView.setAlpha(retainedAlpha);

    final ViewGroup currentHost = headlessHostView(plugin);
    ViewGroup target = retainedParent;
    if (target != null && !canHostRelease(target, currentHost)) {
      target = null;
    }
    if (target == null) {
      target = currentHost;
      retainedParentIndex = 0;
    }
    if (target == null || retainedView.getParent() != null) {
      clearBackgroundSnapshot();
      return;
    }
    final int index = Math.max(0, Math.min(retainedParentIndex, target.getChildCount()));
    target.addView(retainedView, index, retainedLayoutParams);
    clearBackgroundSnapshot();
  }

  private void clearBackgroundSnapshot() {
    backgroundSnapshotTaken = false;
    backgroundLayoutParams = null;
    backgroundAlpha = null;
    backgroundParent = null;
    backgroundParentIndex = -1;
  }

  /**
   * Whether a snapshotted parent can still host the released runtime.
   *
   * <p>The snapshot is taken by the first presentation and outlives it, while
   * the release happens when the user opens the App — which is precisely when
   * the Activity may have been replaced. A snapshot taken under the previous
   * Activity then names a view tree that is no longer on screen.
   */
  private static boolean canHostRelease(
          @NonNull ViewGroup retainedParent,
          @Nullable ViewGroup currentHost
  ) {
    // A parent torn down with its Activity is no longer attached to a window;
    // re-hosting there would leave the runtime windowless all over again.
    if (!retainedParent.isAttachedToWindow()) {
      return false;
    }
    // A live Activity always owns the headless host view, so a snapshot naming
    // anything else belongs to the Activity that has since been replaced.
    return currentHost == null || retainedParent == currentHost;
  }

  private void restoreBackgroundAttachment(
          @NonNull View view,
          @NonNull ViewGroup.LayoutParams retainedLayoutParams
  ) {
    // A headless runtime created by a background CDM / PendingIntent wake-up
    // had no Activity and therefore no window to snapshot. Once the user has
    // opened the App, the Activity exists: host the released runtime the same
    // way HeadlessInAppWebView#prepare would have, so the always-visible hack
    // is armed on an attached View instead of a windowless one.
    final ViewGroup currentHost = headlessHostView(plugin);
    ViewGroup retainedParent = backgroundParent;
    if (retainedParent != null && !canHostRelease(retainedParent, currentHost)) {
      // Opening the App is also the moment the Activity can change, so fall
      // back to the Activity that is actually on screen now.
      retainedParent = null;
    }
    final ViewGroup target = retainedParent != null ? retainedParent : currentHost;
    final int targetIndex = retainedParent != null ? backgroundParentIndex : 0;
    if (target == null || view.getParent() == target) {
      return;
    }
    if (view.getParent() != null) {
      // PlatformView disposal may detach its presentation container after this
      // callback. The unconditional stabilization pass scheduled by
      // prepareForBackgroundRuntime owns that post-dispose state.
      return;
    }
    final int index = Math.max(0, Math.min(targetIndex, target.getChildCount()));
    target.addView(view, index, retainedLayoutParams);
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
      clearBackgroundSnapshot();
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
