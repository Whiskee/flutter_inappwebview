package com.pichillilorenzo.flutter_inappwebview_android;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
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
    ReflectionHelpers.setStaticField(WebViewStartupCoordinator.class, "startupError", null);
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
    if (browser != null) browser.destroy();
    if (browserManager != null) browserManager.dispose();
    if (headlessManager != null) headlessManager.dispose();
    shadowOf(Looper.getMainLooper()).idle();
    compat.close();
    features.close();
  }

  private InAppWebView startHeadless() {
    headlessManager = new HeadlessInAppWebViewManager(plugin);
    plugin.headlessInAppWebViewManager = headlessManager;
    HashMap<String, Object> params = new HashMap<>();
    params.put("initialSettings", new HashMap<String, Object>());
    params.put("pullToRefreshSettings", new HashMap<String, Object>());
    params.put("initialUserScripts", initialUserScripts);
    HashMap<String, Object> request = new HashMap<>();
    request.put("url", URL);
    params.put("initialUrlRequest", request);
    headlessManager.run("headless-test", params);
    return headlessManager.webViews.get("headless-test").flutterWebView.webView;
  }

  private InAppWebView startBrowser(boolean useInitialData) {
    browserManager = new InAppBrowserManager(plugin);
    Bundle extras = new Bundle();
    extras.putString("id", "browser-test");
    extras.putString("managerId", browserManager.id);
    extras.putInt("windowId", -1);
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
}
