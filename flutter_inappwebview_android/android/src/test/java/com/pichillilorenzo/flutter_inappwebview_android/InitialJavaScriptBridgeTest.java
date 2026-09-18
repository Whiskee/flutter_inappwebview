package com.pichillilorenzo.flutter_inappwebview_android;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.WebView;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;
import com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview.HeadlessInAppWebViewManager;
import com.pichillilorenzo.flutter_inappwebview_android.in_app_browser.InAppBrowserActivity;
import com.pichillilorenzo.flutter_inappwebview_android.in_app_browser.InAppBrowserManager;
import com.pichillilorenzo.flutter_inappwebview_android.plugin_scripts_js.JavaScriptBridgeJS;
import com.pichillilorenzo.flutter_inappwebview_android.types.PluginScript;
import com.pichillilorenzo.flutter_inappwebview_android.types.UserScript;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.InAppWebView;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.FlutterWebView;
import com.pichillilorenzo.flutter_inappwebview_android.webview.InAppWebViewManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Runs production entry points with a paused UI queue, without a Chromium process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class InitialJavaScriptBridgeTest {
  private static final String URL = "https://example.test/plugin";
  private MockedStatic<WebViewFeature> features;
  private MockedStatic<WebViewCompat> compat;
  private InAppWebViewFlutterPlugin plugin;
  private HeadlessInAppWebViewManager headlessManager;
  private InAppBrowserManager browserManager;
  private ActivityController<InAppBrowserActivity> browser;
  private FlutterWebView popup;
  private boolean popupTransportDelivered;
  private static final int POPUP_ID = 77;
  private final List<String> registeredScripts = new ArrayList<>();
  private final ArrayList<HashMap<String, Object>> initialUserScripts = new ArrayList<>();

  private void addInitialUserScript(String source) {
    HashMap<String, Object> script = new HashMap<>();
    script.put("source", source);
    script.put("injectionTime", 0);
    script.put("allowedOriginRules", new ArrayList<>(java.util.Collections.singletonList("*")));
    script.put("forMainFrameOnly", true);
    initialUserScripts.add(script);
  }

  private int registeredIndex(String sourceFragment) {
    for (int i = 0; i < registeredScripts.size(); i++) {
      if (registeredScripts.get(i).contains(sourceFragment)) return i;
    }
    return -1;
  }

  private void assertBridgeBeforeInitialUsers() {
    int bridge = registeredIndex(".callHandler = function(");
    int first = registeredIndex("callHandler('initial-user-1')");
    int second = registeredIndex("callHandler('initial-user-2')");
    assertTrue("Bridge must be registered", bridge >= 0);
    assertTrue("Initial user script must follow Bridge", first > bridge);
    assertTrue("Initial user scripts must keep their order", second > first);
  }

  @Before
  public void setUp() {
    // The coordinator deliberately caches process-wide startup state. Each test
    // models a fresh engine so a previous retry cannot bypass this test's startup.
    Object startupState = ReflectionHelpers.getStaticField(WebViewStartupCoordinator.class, "state");
    ReflectionHelpers.setStaticField(WebViewStartupCoordinator.class, "state",
            startupState.getClass().getEnumConstants()[0]);
    List<?> pendingStartup = ReflectionHelpers.getStaticField(WebViewStartupCoordinator.class, "PENDING_CALLBACKS");
    pendingStartup.clear();
    features = mockStatic(WebViewFeature.class);
    features.when(() -> WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)).thenReturn(true);
    compat = mockStatic(WebViewCompat.class);
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenAnswer(invocation -> {
              assertNull(shadowOf((WebView) invocation.getArgument(0)).getLastLoadedUrl());
              registeredScripts.add(invocation.getArgument(1));
              return mock(ScriptHandler.class);
            });
    plugin = new InAppWebViewFlutterPlugin();
    plugin.applicationContext = RuntimeEnvironment.getApplication();
    plugin.messenger = mock(BinaryMessenger.class);
  }

  @After
  public void tearDown() {
    if (popup != null) popup.dispose();
    if (browser != null) browser.destroy();
    if (browserManager != null) browserManager.dispose();
    if (headlessManager != null) headlessManager.dispose();
    if (plugin.inAppWebViewManager != null) plugin.inAppWebViewManager.dispose();
    shadowOf(Looper.getMainLooper()).idle();
    compat.close();
    features.close();
  }

  private InAppWebView startHeadless() {
    return startHeadless(true);
  }

  private InAppWebView startHeadless(boolean hasInitialUrl) {
    headlessManager = new HeadlessInAppWebViewManager(plugin);
    plugin.headlessInAppWebViewManager = headlessManager;
    HashMap<String, Object> params = new HashMap<>();
    params.put("initialSettings", new HashMap<String, Object>());
    params.put("pullToRefreshSettings", new HashMap<String, Object>());
    params.put("initialUserScripts", initialUserScripts);
    HashMap<String, Object> request = new HashMap<>();
    request.put("url", URL);
    if (hasInitialUrl) params.put("initialUrlRequest", request);
    headlessManager.run("headless-test", params);
    return headlessManager.webViews.get("headless-test").flutterWebView.webView;
  }

  private InAppWebView startBrowser(boolean useInitialData) {
    return startBrowser(useInitialData, -1);
  }

  private InAppWebView startBrowser(boolean useInitialData, int windowId) {
    browserManager = new InAppBrowserManager(plugin);
    Bundle extras = new Bundle();
    extras.putString("id", "browser-test");
    extras.putString("managerId", browserManager.id);
    extras.putInt("windowId", windowId);
    extras.putSerializable("settings", new HashMap<String, Object>());
    extras.putSerializable("initialUserScripts", initialUserScripts);
    extras.putSerializable("menuItems", new ArrayList<>());
    extras.putSerializable("pullToRefreshInitialSettings", new HashMap<String, Object>());
    if (useInitialData) {
      extras.putString("initialData", "<html><body>plugin</body></html>");
      extras.putString("initialMimeType", "text/html");
      extras.putString("initialEncoding", "utf-8");
      extras.putString("initialBaseUrl", URL);
    } else {
      HashMap<String, Object> request = new HashMap<>();
      request.put("url", URL);
      extras.putSerializable("initialUrlRequest", request);
    }
    Intent intent = new Intent(plugin.applicationContext, InAppBrowserActivity.class);
    intent.putExtras(extras);
    browser = Robolectric.buildActivity(InAppBrowserActivity.class, intent);
    plugin.activity = browser.get();
    browser.create();
    return browser.get().webView;
  }

  private void preparePopupTransport() {
    plugin.inAppWebViewManager = new InAppWebViewManager(plugin);
    Message message = Message.obtain(new Handler(Looper.getMainLooper()), () -> {
      assertTrue("Popup scripts must follow the transport handoff", registeredScripts.isEmpty());
      popupTransportDelivered = true;
    });
    message.obj = mock(WebView.WebViewTransport.class);
    plugin.inAppWebViewManager.windowWebViewMessages.put(POPUP_ID, message);
  }

  private HashMap<String, Object> popupParams() {
    HashMap<String, Object> params = new HashMap<>();
    params.put("windowId", POPUP_ID);
    params.put("initialSettings", new HashMap<String, Object>());
    params.put("pullToRefreshSettings", new HashMap<String, Object>());
    params.put("initialUserScripts", initialUserScripts);
    return params;
  }

  private InAppWebView createPopup() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    preparePopupTransport();
    popup = new FlutterWebView(plugin, plugin.applicationContext, "popup-test", popupParams());
    return popup.webView;
  }

  private MethodChannel.Result requestReadiness(InAppWebView webView) {
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    return result;
  }


  private MethodCall explicitLoad(String method, String url) {
    HashMap<String, Object> arguments = new HashMap<>();
    if ("loadUrl".equals(method)) {
      arguments.put("urlRequest", java.util.Collections.singletonMap("url", url));
    } else if ("postUrl".equals(method)) {
      arguments.put("url", url);
      arguments.put("postData", new byte[] {1, 2, 3});
    } else if ("loadData".equals(method)) {
      arguments.put("data", "<html>explicit</html>");
      arguments.put("mimeType", "text/html");
      arguments.put("encoding", "utf-8");
      arguments.put("baseUrl", url);
      arguments.put("historyUrl", url);
    } else {
      arguments.put("assetFilePath", "fixture.html");
    }
    return new MethodCall(method, arguments);
  }

  private MethodChannel.Result requestExplicitLoad(InAppWebView webView, String method, String url) {
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(explicitLoad(method, url), result);
    return result;
  }

  @Test
  public void createdCallbackExplicitLoadWaitsForInitialScripts() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    doAnswer(invocation -> {
      assertBridgeBeforeInitialUsers();
      return null;
    }).when(result).success(any());
    // Simulate an asynchronous Dart callback round trip from the actual native
    // creation event. The load must not reenter platform-view construction.
    doAnswer(invocation -> {
      java.nio.ByteBuffer message = ((java.nio.ByteBuffer) invocation.getArgument(1)).duplicate();
      message.flip();
      MethodCall event = io.flutter.plugin.common.StandardMethodCodec.INSTANCE.decodeMethodCall(message);
      if ("onWebViewCreated".equals(event.method)) {
        new Handler(Looper.getMainLooper()).post(() -> {
          InAppWebView webView = headlessManager.webViews.get("headless-test").flutterWebView.webView;
          webView.channelDelegate.onMethodCall(explicitLoad("loadUrl", URL + "/explicit"), result);
        });
      }
      return null;
    }).when(plugin.messenger).send(
            startsWith("com.pichillilorenzo/flutter_headless_inappwebview_"),
            any(java.nio.ByteBuffer.class), nullable(BinaryMessenger.BinaryReply.class));
    InAppWebView webView = startHeadless();
    shadowOf(Looper.getMainLooper()).idle();
    verify(result).success(true);
    assertEquals(URL + "/explicit", shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void explicitDataWaitsForInitialScriptRetry() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless(false);
    MethodChannel.Result result = requestExplicitLoad(webView, "loadData", URL);
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(shadowOf(webView).getLastLoadDataWithBaseURL());
    verifyNoInteractions(result);
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(shadowOf(webView).getLastLoadDataWithBaseURL());
    verify(result).success(true);
  }

  @Test
  @Config(shadows = RecordingPostWebView.class)
  public void explicitPostWaitsForInitialScriptRetry() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless(false);
    MethodChannel.Result result = requestExplicitLoad(webView, "postUrl", URL);
    shadowOf(Looper.getMainLooper()).idle();
    verifyNoInteractions(result);
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    verify(result).success(true);
    RecordingPostWebView shadow = org.robolectric.shadow.api.Shadow.extract(webView);
    assertEquals(URL, shadow.lastPostedUrl);
    assertArrayEquals(new byte[] {1, 2, 3}, shadow.lastPostedData);
  }

  @org.robolectric.annotation.Implements(WebView.class)
  public static class RecordingPostWebView extends org.robolectric.shadows.ShadowWebView {
    String lastPostedUrl;
    byte[] lastPostedData;

    @org.robolectric.annotation.Implementation
    protected void postUrl(String url, byte[] postData) {
      lastPostedUrl = url;
      lastPostedData = postData;
    }
  }

  @Test
  public void explicitFileWaitsForInitialScriptRetry() throws Exception {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless(false);
    try (MockedStatic<Util> util = mockStatic(Util.class)) {
      util.when(() -> Util.getUrlAsset(plugin, "fixture.html")).thenReturn(URL);
      MethodChannel.Result result = requestExplicitLoad(webView, "loadFile", URL);
      shadowOf(Looper.getMainLooper()).idle();
      assertNull(shadowOf(webView).getLastLoadedUrl());
      verifyNoInteractions(result);
      startup.get(0).onResult(mock(WebViewStartUpResult.class));
      shadowOf(Looper.getMainLooper()).idle();
      assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
      verify(result).success(true);
    }
  }

  @Test
  public void explicitFileFailureCompletesResultWithError() throws Exception {
    InAppWebView webView = startHeadless(false);
    try (MockedStatic<Util> util = mockStatic(Util.class)) {
      util.when(() -> Util.getUrlAsset(plugin, "fixture.html"))
              .thenThrow(new java.io.IOException("missing asset"));
      MethodChannel.Result result = requestExplicitLoad(webView, "loadFile", URL);
      shadowOf(Looper.getMainLooper()).idle();
      verify(result).error(anyString(), contains("missing asset"), isNull());
      verify(result, never()).success(any());
      assertNull(shadowOf(webView).getLastLoadedUrl());
    }
  }

  @Test
  public void failedInitialRegistrationRejectsExplicitLoad() {
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenThrow(new IllegalStateException("initial registration failed"));
    InAppWebView webView = startHeadless(false);
    MethodChannel.Result result = requestExplicitLoad(webView, "loadUrl", URL);
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(shadowOf(webView).getLastLoadedUrl());
    verify(result).error(anyString(), contains("initial registration failed"), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void disposeDuringRetryRejectsPendingExplicitLoad() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless(false);
    shadowOf(Looper.getMainLooper()).idle();
    MethodChannel.Result result = requestExplicitLoad(webView, "loadUrl", URL);
    shadowOf(Looper.getMainLooper()).idle();
    headlessManager.webViews.get("headless-test").dispose();
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertNotEquals(URL, shadowOf(webView).getLastLoadedUrl());
    verify(result).error(anyString(), contains("disposed"), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void explicitLoadsKeepOrderAfterConfiguredInitialLoad() {
    InAppWebView webView = startHeadless();
    List<String> completed = new ArrayList<>();
    for (String suffix : new String[] {"/one", "/two"}) {
      MethodChannel.Result result = mock(MethodChannel.Result.class);
      doAnswer(invocation -> {
        assertEquals(URL + suffix, shadowOf(webView).getLastLoadedUrl());
        completed.add(suffix);
        return null;
      }).when(result).success(any());
      webView.channelDelegate.onMethodCall(explicitLoad("loadUrl", URL + suffix), result);
    }
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(java.util.Arrays.asList("/one", "/two"), completed);
    assertEquals(URL + "/two", shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void initializedExplicitLoadDoesNotWaitForDynamicScripts() {
    InAppWebView webView = startHeadless(false);
    shadowOf(Looper.getMainLooper()).idle();
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenReturn(mock(ScriptHandler.class));
    addInitialUserScript("window.dynamicUser = true;");
    webView.userContentController.addUserOnlyScript(UserScript.fromMap(initialUserScripts.get(0)));
    MethodChannel.Result result = requestExplicitLoad(webView, "loadUrl", URL);
    // No UI-queue pump: initialized navigations preserve their synchronous path.
    verify(result).success(true);
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void stopLoadingCancelsBothConfiguredAndExplicitPendingLoads() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless();
    shadowOf(Looper.getMainLooper()).idle();
    MethodChannel.Result pending = requestExplicitLoad(webView, "loadUrl", URL + "/cancelled");
    webView.channelDelegate.onMethodCall(new MethodCall("stopLoading", null), mock(MethodChannel.Result.class));
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(shadowOf(webView).getLastLoadedUrl());
    verify(pending).success(true);
    MethodChannel.Result next = requestExplicitLoad(webView, "loadUrl", URL + "/new");
    verify(next).success(true);
    assertEquals(URL + "/new", shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void popupExplicitLoadWaitsForPreparationAndTransport() {
    InAppWebView webView = createPopup();
    MethodChannel.Result result = requestExplicitLoad(webView, "loadUrl", URL);
    shadowOf(Looper.getMainLooper()).idle();
    verifyNoInteractions(result);
    assertNull(shadowOf(webView).getLastLoadedUrl());
    popup.makeInitialLoad(popupParams());
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(popupTransportDelivered);
    assertBridgeBeforeInitialUsers();
    verify(result).success(true);
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }


  @Test
  public void initializedFastPathCannotOvertakeAnEarlierPendingNavigation() {
    features.when(() -> WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)).thenReturn(false);
    InAppWebView webView = startHeadless(false);
    List<String> completed = new ArrayList<>();
    MethodChannel.Result first = mock(MethodChannel.Result.class);
    MethodChannel.Result second = mock(MethodChannel.Result.class);
    doAnswer(invocation -> {
      assertNotNull(shadowOf(webView).getJavascriptInterface(JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME()));
      assertEquals(URL + "/first", shadowOf(webView).getLastLoadedUrl());
      completed.add("first");
      return null;
    }).when(first).success(any());
    doAnswer(invocation -> {
      assertEquals(URL + "/second", shadowOf(webView).getLastLoadedUrl());
      completed.add("second");
      return null;
    }).when(second).success(any());
    // Native configured initialization finishes before this second method call,
    // but the first explicit call's readiness message has not executed yet.
    new Handler(Looper.getMainLooper()).post(() ->
            webView.channelDelegate.onMethodCall(explicitLoad("loadUrl", URL + "/second"), second));
    webView.channelDelegate.onMethodCall(explicitLoad("loadUrl", URL + "/first"), first);
    verifyNoInteractions(first);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(java.util.Arrays.asList("first", "second"), completed);
    assertEquals(URL + "/second", shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void initializedNavigationIgnoresLaterDynamicRegistrationFailure() {
    InAppWebView webView = startHeadless(false);
    shadowOf(Looper.getMainLooper()).idle();
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenThrow(new IllegalStateException("dynamic script failed"));
    addInitialUserScript("window.dynamicUser = true;");
    webView.userContentController.addUserOnlyScript(UserScript.fromMap(initialUserScripts.get(0)));
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(webView.userContentController.getScriptRegistrationError());
    MethodChannel.Result result = requestExplicitLoad(webView, "loadUrl", URL);
    verify(result).success(true);
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void browserExplicitLoadFollowsConfiguredInitialLoad() {
    InAppWebView webView = startBrowser(false);
    MethodChannel.Result result = requestExplicitLoad(webView, "loadUrl", URL + "/explicit");
    verifyNoInteractions(result);
    shadowOf(Looper.getMainLooper()).idle();
    verify(result).success(true);
    assertEquals(URL + "/explicit", shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void stopDuringRetryDoesNotCancelTheNextNavigation() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless(false);
    shadowOf(Looper.getMainLooper()).idle();
    MethodChannel.Result previous = requestExplicitLoad(webView, "loadUrl", URL + "/cancelled");
    webView.channelDelegate.onMethodCall(new MethodCall("stopLoading", null), mock(MethodChannel.Result.class));
    MethodChannel.Result next = requestExplicitLoad(webView, "loadUrl", URL + "/new");
    doAnswer(invocation -> {
      assertNull("Cancelled URL must never load", shadowOf(webView).getLastLoadedUrl());
      return null;
    }).when(previous).success(any());
    shadowOf(Looper.getMainLooper()).idle();
    verifyNoInteractions(previous, next);
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    verify(previous).success(true);
    verify(next).success(true);
    assertEquals(URL + "/new", shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void headlessWithoutActivityRegistersAndLoadsWithoutAttaching() {
    InAppWebView webView = startHeadless();
    assertFalse(webView.isAttachedToWindow());
    assertNull(shadowOf(webView).getLastLoadedUrl());
    assertNull(shadowOf(webView).getJavascriptInterface(JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME()));
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    verifyNoInteractions(result);
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(webView.isAttachedToWindow());
    assertFalse(registeredScripts.isEmpty());
    assertNotNull(shadowOf(webView).getJavascriptInterface(JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME()));
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
    verify(result).success(true);
  }

  @Test
  public void browserUrlWaitsForBridgeRegistration() {
    InAppWebView webView = startBrowser(false);
    assertNull(shadowOf(webView).getLastLoadedUrl());
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(shadowOf(webView).getJavascriptInterface(JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME()));
    assertFalse(registeredScripts.isEmpty());
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void browserDataWaitsForBridgeRegistration() {
    InAppWebView webView = startBrowser(true);
    assertNull(shadowOf(webView).getLastLoadDataWithBaseURL());
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(shadowOf(webView).getJavascriptInterface(JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME()));
    assertNotNull(shadowOf(webView).getLastLoadDataWithBaseURL());
  }

  @Test
  public void failedScriptRegistrationDoesNotLoadBrowser() {
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenThrow(new IllegalStateException("script registration failed"));
    InAppWebView webView = startBrowser(false);
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void disposeBeforeQueueRunsCompletesReadinessWithError() {
    InAppWebView webView = startHeadless();
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    headlessManager.webViews.get("headless-test").dispose();
    shadowOf(Looper.getMainLooper()).idle();
    verify(result).error(anyString(), contains("disposed"), isNull());
    verify(result, never()).success(any());
    assertTrue(registeredScripts.isEmpty());
    assertNotEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void removeAndReaddInvalidatesOnlyTheOldRegistration() {
    InAppWebView webView = startHeadless();
    PluginScript script = webView.userContentController.getPluginScriptAsList().iterator().next();
    webView.userContentController.removePluginScript(script);
    webView.userContentController.addPluginScript(script);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, registeredScripts.stream().filter(source -> source.contains(script.getSource())).count());
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void initialLoadWaitsForStartupRetryToFinish() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup = new ArrayList<>();
    compat.when(() -> WebViewCompat.startUpWebView(any(), any(), any(WebViewOutcomeReceiver.class))).thenAnswer(invocation -> {
      startup.add(invocation.getArgument(2));
      return null;
    });
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenThrow(new IllegalStateException("Must be started before we block"))
            .thenAnswer(invocation -> {
              registeredScripts.add(invocation.getArgument(1));
              return mock(ScriptHandler.class);
            });
    InAppWebView webView = startHeadless();
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    assertNull(shadowOf(webView).getLastLoadedUrl());
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void aTransientWebViewStartupFailureCanBeRetried() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            new ArrayList<>();
    compat.when(() -> WebViewCompat.startUpWebView(
            any(), any(), any(WebViewOutcomeReceiver.class))).thenAnswer(invocation -> {
      startup.add(invocation.getArgument(2));
      return null;
    });
    WebViewStartupCoordinator.Callback first = mock(WebViewStartupCoordinator.Callback.class);
    WebViewStartupCoordinator.Callback second = mock(WebViewStartupCoordinator.Callback.class);

    WebViewStartupCoordinator.ensureStarted(plugin.applicationContext, first);
    assertEquals(1, startup.size());
    startup.get(0).onError(mock(WebViewStartupException.class));
    shadowOf(Looper.getMainLooper()).idle();
    verify(first).onError(any());

    WebViewStartupCoordinator.ensureStarted(plugin.applicationContext, second);
    assertEquals("a later owner must receive a fresh startup attempt", 2, startup.size());
    startup.get(1).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    verify(second).onSuccess();
    verify(second, never()).onError(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void unsupportedAsyncStartupFallsBackToSynchronousWebViewStartup() {
    compat.when(() -> WebViewCompat.startUpWebView(
            any(), any(), any(WebViewOutcomeReceiver.class)))
            .thenThrow(new UnsupportedOperationException("unsupported fixture"));
    WebViewStartupCoordinator.Callback callback =
            mock(WebViewStartupCoordinator.Callback.class);

    WebViewStartupCoordinator.ensureStarted(plugin.applicationContext, callback);
    shadowOf(Looper.getMainLooper()).idle();

    verify(callback).onSuccess();
    verify(callback, never()).onError(any());
  }

  @Test
  public void headlessInitialUserScriptsFollowBridge() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    InAppWebView webView = startHeadless();
    assertTrue("All initial registration must remain asynchronous", registeredScripts.isEmpty());
    shadowOf(Looper.getMainLooper()).idle();
    assertBridgeBeforeInitialUsers();
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void browserInitialUserScriptsFollowBridge() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    InAppWebView webView = startBrowser(false);
    shadowOf(Looper.getMainLooper()).idle();
    assertBridgeBeforeInitialUsers();
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @SuppressWarnings("unchecked")
  private List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>>
      deferStartupAndFailFirstRegistrationMatching(String fragment) {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup = new ArrayList<>();
    compat.when(() -> WebViewCompat.startUpWebView(any(), any(), any(WebViewOutcomeReceiver.class)))
            .thenAnswer(invocation -> {
              startup.add(invocation.getArgument(2));
              return null;
            });
    boolean[] failed = {false};
    compat.when(() -> WebViewCompat.addDocumentStartJavaScript(any(WebView.class), anyString(), anySet()))
            .thenAnswer(invocation -> {
              String source = invocation.getArgument(1);
              if (!failed[0] && source.contains(fragment)) {
                failed[0] = true;
                throw new IllegalStateException("Must be started before we block");
              }
              assertNull(shadowOf((WebView) invocation.getArgument(0)).getLastLoadedUrl());
              registeredScripts.add(source);
              return mock(ScriptHandler.class);
            });
    return startup;
  }

  @Test
  public void bridgeRetryBlocksLaterScriptsAndReadiness() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless();
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    assertEquals("Only the preceding Promise polyfill may register", 1, registeredScripts.size());
    assertNull(shadowOf(webView).getLastLoadedUrl());
    verifyNoInteractions(result);
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertBridgeBeforeInitialUsers();
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
    verify(result).success(true);
  }

  @Test
  public void userScriptRetryKeepsLaterUserScriptsBehindIt() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching("callHandler('initial-user-1')");
    InAppWebView webView = startHeadless();
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    assertEquals(-1, registeredIndex("callHandler('initial-user-2')"));
    assertNull(shadowOf(webView).getLastLoadedUrl());
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertBridgeBeforeInitialUsers();
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void removedUserScriptDoesNotRegisterFromQueuedTask() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    InAppWebView webView = startHeadless();
    UserScript user = webView.userContentController.getUserOnlyScriptAsList().iterator().next();
    assertTrue(webView.userContentController.removeUserOnlyScript(user));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(-1, registeredIndex("callHandler('initial-user-1')"));
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void disposeDuringBridgeRetryDoesNotRegisterOrLoadLater() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless();
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    headlessManager.webViews.get("headless-test").dispose();
    int registrationsBeforeStartup = registeredScripts.size();
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(registrationsBeforeStartup, registeredScripts.size());
    assertEquals(-1, registeredIndex("callHandler('initial-user-1')"));
    assertNotEquals(URL, shadowOf(webView).getLastLoadedUrl());
    verify(result).error(anyString(), contains("disposed"), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void removeAndReaddRetryingUserIgnoresOldStartupFailure() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching("callHandler('initial-user-1')");
    InAppWebView webView = startHeadless();
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    UserScript first = webView.userContentController.getUserOnlyScriptAsList().iterator().next();
    assertTrue(webView.userContentController.removeUserOnlyScript(first));
    assertTrue(webView.userContentController.addUserOnlyScript(first));
    startup.get(0).onError(mock(WebViewStartupException.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(webView.userContentController.getScriptRegistrationError());
    assertTrue(registeredIndex("callHandler('initial-user-1')") > registeredIndex("callHandler('initial-user-2')"));
    assertEquals(1, registeredScripts.stream().filter(source -> source.contains("callHandler('initial-user-1')")).count());
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void removeAllPendingUsersKeepsBridgeRegistration() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    InAppWebView webView = startHeadless();
    webView.userContentController.removeAllUserOnlyScripts();
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredIndex(".callHandler = function(") >= 0);
    assertEquals(-1, registeredIndex("callHandler('initial-user-1')"));
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void startupFailureCompletesReadinessWithError() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless();
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    startup.get(0).onError(mock(WebViewStartupException.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertNotNull(webView.userContentController.getScriptRegistrationError());
    assertNull(shadowOf(webView).getLastLoadedUrl());
    verify(result).error(anyString(), any(), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void namedContentWorldSetupUsesTheSameDeferredRetryBarrier() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    HashMap<String, Object> world = new HashMap<>();
    world.put("name", "isolated");
    initialUserScripts.get(0).put("contentWorld", world);
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching("var contentWorldNames =");
    InAppWebView webView = startHeadless();
    assertTrue(registeredScripts.isEmpty());
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    assertTrue(registeredScripts.isEmpty());
    assertNull(shadowOf(webView).getLastLoadedUrl());
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredIndex("callHandler('initial-user-1')") >= 0);
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void addUserScriptChannelWaitsForQueuedRegistration() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = startHeadless();
    shadowOf(Looper.getMainLooper()).idle();
    addInitialUserScript("window.flutter_inappwebview.callHandler('dynamic-user');");
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    webView.channelDelegate.onMethodCall(new MethodCall("addUserScript",
            java.util.Collections.singletonMap("userScript", initialUserScripts.get(0))), result);
    verifyNoInteractions(result);
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredIndex("callHandler('dynamic-user')") > registeredIndex(".callHandler = function("));
    verify(result).success(true);
    assertEquals(URL, shadowOf(webView).getLastLoadedUrl());
  }

  @Test
  public void popupReadinessWaitsForPreparationBeforeTransport() {
    InAppWebView webView = createPopup();
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    assertFalse(webView.isAttachedToWindow());
    assertTrue(registeredScripts.isEmpty());
    verifyNoInteractions(result);

    popup.makeInitialLoad(popupParams());
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(popupTransportDelivered);
    assertFalse("Readiness must not depend on attachment", webView.isAttachedToWindow());
    assertBridgeBeforeInitialUsers();
    verify(result).success(true);
  }

  @Test
  public void popupReadinessNeverSucceedsBeforeScriptsRegister() {
    InAppWebView webView = createPopup();
    popup.makeInitialLoad(popupParams());
    MethodChannel.Result result = mock(MethodChannel.Result.class);
    doAnswer(invocation -> {
      assertTrue(popupTransportDelivered);
      assertBridgeBeforeInitialUsers();
      return null;
    }).when(result).success(any());
    webView.channelDelegate.onMethodCall(new MethodCall("waitForInitialJavaScriptBridgeReady", null), result);
    shadowOf(Looper.getMainLooper()).idle();
    verify(result).success(true);
    verify(result, never()).error(anyString(), any(), any());
  }

  @Test
  public void popupDisposeBeforePreparationReleasesReadinessWithError() {
    InAppWebView webView = createPopup();
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    verifyNoInteractions(result);
    popup.dispose();
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredScripts.isEmpty());
    verify(result).error(anyString(), contains("disposed"), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void popupDisposeBeforeDeferredPreparationCannotRegisterScripts() {
    InAppWebView webView = createPopup();
    popup.makeInitialLoad(popupParams());
    MethodChannel.Result result = requestReadiness(webView);
    popup.dispose();
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredScripts.isEmpty());
    verify(result).error(anyString(), contains("disposed"), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void popupReadinessWaitsForOrderedStartupRetry() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = createPopup();
    popup.makeInitialLoad(popupParams());
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    verifyNoInteractions(result);
    assertEquals(-1, registeredIndex("callHandler('initial-user-1')"));
    startup.get(0).onResult(mock(WebViewStartUpResult.class));
    shadowOf(Looper.getMainLooper()).idle();
    assertBridgeBeforeInitialUsers();
    verify(result).success(true);
  }

  @Test
  public void popupStartupFailureReleasesReadinessWithError() {
    List<WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>> startup =
            deferStartupAndFailFirstRegistrationMatching(".callHandler = function(");
    InAppWebView webView = createPopup();
    popup.makeInitialLoad(popupParams());
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, startup.size());
    startup.get(0).onError(mock(WebViewStartupException.class));
    shadowOf(Looper.getMainLooper()).idle();
    verify(result).error(anyString(), any(), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void missingPopupTransportFailsInsteadOfLeavingReadinessPending() {
    InAppWebView webView = createPopup();
    plugin.inAppWebViewManager.windowWebViewMessages.remove(POPUP_ID);
    popup.makeInitialLoad(popupParams());
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredScripts.isEmpty());
    verify(result).error(anyString(), contains("transport"), isNull());
    verify(result, never()).success(any());
  }

  @Test
  public void browserPopupUsesTheSameOrderedPreparationBarrier() {
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-1');");
    addInitialUserScript("window.flutter_inappwebview.callHandler('initial-user-2');");
    preparePopupTransport();
    InAppWebView webView = startBrowser(false, POPUP_ID);
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(popupTransportDelivered);
    assertBridgeBeforeInitialUsers();
    verify(result).success(true);
  }

  @Test
  public void popupWithoutDocumentStartSupportKeepsFallbackScripts() {
    features.when(() -> WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)).thenReturn(false);
    InAppWebView webView = createPopup();
    popup.makeInitialLoad(popupParams());
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(popupTransportDelivered);
    assertTrue(registeredScripts.isEmpty());
    String source = webView.userContentController.generateCodeForDocumentStart();
    int bridge = source.indexOf(".callHandler = function(");
    assertTrue(bridge >= 0);
    assertTrue(source.indexOf("callHandler('initial-user-1')") > bridge);
    verify(result).success(true);
  }

  @Test
  public void popupWithBridgeDisabledAndNoScriptsStillCompletes() {
    preparePopupTransport();
    HashMap<String, Object> params = popupParams();
    HashMap<String, Object> settings = new HashMap<>();
    settings.put("javaScriptBridgeEnabled", false);
    params.put("initialSettings", settings);
    popup = new FlutterWebView(plugin, plugin.applicationContext, "empty-popup", params);
    MethodChannel.Result result = requestReadiness(popup.webView);
    shadowOf(Looper.getMainLooper()).idle();
    verifyNoInteractions(result);
    popup.makeInitialLoad(params);
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(popupTransportDelivered);
    assertTrue(registeredScripts.isEmpty());
    verify(result).success(true);
  }

  @Test
  public void popupTransportFailureReleasesReadinessWithError() {
    InAppWebView webView = createPopup();
    WebView.WebViewTransport transport = (WebView.WebViewTransport)
            plugin.inAppWebViewManager.windowWebViewMessages.get(POPUP_ID).obj;
    doThrow(new IllegalStateException("popup handoff failed")).when(transport).setWebView(webView);
    MethodChannel.Result result = requestReadiness(webView);
    shadowOf(Looper.getMainLooper()).idle();
    verifyNoInteractions(result);
    popup.makeInitialLoad(popupParams());
    shadowOf(Looper.getMainLooper()).idle();
    assertTrue(registeredScripts.isEmpty());
    verify(result).error(anyString(), contains("popup handoff failed"), isNull());
    verify(result, never()).success(any());
  }
}
