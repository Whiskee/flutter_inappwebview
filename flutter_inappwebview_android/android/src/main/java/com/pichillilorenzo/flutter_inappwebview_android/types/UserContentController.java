package com.pichillilorenzo.flutter_inappwebview_android.types;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.pichillilorenzo.flutter_inappwebview_android.Util;
import com.pichillilorenzo.flutter_inappwebview_android.WebViewStartupCoordinator;
import com.pichillilorenzo.flutter_inappwebview_android.plugin_scripts_js.JavaScriptBridgeJS;
import com.pichillilorenzo.flutter_inappwebview_android.plugin_scripts_js.PluginScriptsUtil;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressLint("RestrictedApi")
public class UserContentController implements Disposable {
  protected static final String LOG_TAG = "UserContentController";

  @NonNull
  private final Set<ContentWorld> contentWorlds = new HashSet<ContentWorld>() {{
    add(ContentWorld.PAGE);
  }};

  private final Map<UserScript, ScriptHandler> scriptHandlerMap = new HashMap<>();
  private final Map<UserScript, Object> pendingUserOnlyScriptRegistrations = new HashMap<>();
  private final Map<PluginScript, Object> pendingPluginScriptRegistrations = new HashMap<>();
  private final Map<UserScript, Throwable> userOnlyScriptRegistrationErrors = new HashMap<>();
  private final Map<PluginScript, Throwable> pluginScriptRegistrationErrors = new HashMap<>();
  private final List<Runnable> scriptRegistrationsCompleteCallbacks = new ArrayList<>();
  private boolean disposed = false;

  @Nullable
  private ScriptHandler contentWorldsCreatorScript;

  @NonNull
  private final Map<UserScriptInjectionTime, LinkedHashSet<UserScript>> userOnlyScripts = new HashMap<UserScriptInjectionTime, LinkedHashSet<UserScript>>() {{
    put(UserScriptInjectionTime.AT_DOCUMENT_START, new LinkedHashSet<UserScript>());
    put(UserScriptInjectionTime.AT_DOCUMENT_END, new LinkedHashSet<UserScript>());
  }};
  @NonNull
  private final Map<UserScriptInjectionTime, LinkedHashSet<PluginScript>> pluginScripts = new HashMap<UserScriptInjectionTime, LinkedHashSet<PluginScript>>() {{
    put(UserScriptInjectionTime.AT_DOCUMENT_START, new LinkedHashSet<PluginScript>());
    put(UserScriptInjectionTime.AT_DOCUMENT_END, new LinkedHashSet<PluginScript>());
  }};

  @Nullable
  public WebView webView;

  public UserContentController(@Nullable WebView webView) {
    this.webView = webView;
  }

  public String generateWrappedCodeForDocumentStart() {
    return Util.replaceAll(
            DOCUMENT_READY_WRAPPER_JS_SOURCE,
            PluginScriptsUtil.VAR_PLACEHOLDER_VALUE,
            generateCodeForDocumentStart());
  }

  public String generateWrappedCodeForDocumentEnd() {
    UserScriptInjectionTime injectionTime = UserScriptInjectionTime.AT_DOCUMENT_END;
    String js = "";
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      // try to reload scripts if they were not loaded during the AT_DOCUMENT_START event
      js += generateCodeForDocumentStart();
    }
    js += generatePluginScriptsCodeAt(injectionTime);
    js += generateUserOnlyScriptsCodeAt(injectionTime);
    js = USER_SCRIPTS_AT_DOCUMENT_END_WRAPPER_JS_SOURCE().replace(PluginScriptsUtil.VAR_PLACEHOLDER_VALUE, js);
    return js;
  }

  public String generateCodeForDocumentStart() {
    UserScriptInjectionTime injectionTime = UserScriptInjectionTime.AT_DOCUMENT_START;
    String js = "";
    js += generatePluginScriptsCodeAt(injectionTime);
    js += generateContentWorldsCreatorCode();
    js += generateUserOnlyScriptsCodeAt(injectionTime);
    js = USER_SCRIPTS_AT_DOCUMENT_START_WRAPPER_JS_SOURCE().replace(PluginScriptsUtil.VAR_PLACEHOLDER_VALUE, js);
    return js;
  }

  public String generateContentWorldsCreatorCode() {
    if (this.contentWorlds.size() == 1) {
      return "";
    }

    StringBuilder source = new StringBuilder();
    LinkedHashSet<PluginScript> pluginScriptsRequired = this.getPluginScriptsRequiredInAllContentWorlds();
    for (PluginScript script : pluginScriptsRequired) {
      source.append(script.getSource());
    }
    List<String> contentWorldsNames = new ArrayList<>();
    for (ContentWorld contentWorld : this.contentWorlds) {
      if (contentWorld.equals(ContentWorld.PAGE)) {
        continue;
      }
      contentWorldsNames.add("'" + escapeContentWorldName(contentWorld.getName()) + "'");
    }

    return CONTENT_WORLDS_GENERATOR_JS_SOURCE()
            .replace(PluginScriptsUtil.VAR_CONTENT_WORLD_NAME_ARRAY, TextUtils.join(", ", contentWorldsNames))
            .replace(PluginScriptsUtil.VAR_JSON_SOURCE_ENCODED, escapeCode(source.toString()));

  }

  public String generatePluginScriptsCodeAt(UserScriptInjectionTime injectionTime) {
    StringBuilder js = new StringBuilder();
    LinkedHashSet<PluginScript> scripts = this.getPluginScriptsAt(injectionTime);
    for (PluginScript script : scripts) {
      String source = ";" + script.getSource();
      source = wrapSourceCodeInContentWorld(script.getContentWorld(), source);
      source = wrapSourceCodeAddChecks(source, script);
      js.append(source);
    }
    return js.toString();
  }

  public String generateUserOnlyScriptsCodeAt(UserScriptInjectionTime injectionTime) {
    StringBuilder js = new StringBuilder();
    LinkedHashSet<UserScript> scripts = this.getUserOnlyScriptsAt(injectionTime);
    for (UserScript script : scripts) {
      String source = ";" + script.getSource();
      source = wrapSourceCodeInContentWorld(script.getContentWorld(), source);
      source = wrapSourceCodeAddChecks(source, script);
      js.append(source);
    }
    return js.toString();
  }

  public String generateCodeForScriptEvaluation(String source, @Nullable ContentWorld contentWorld) {
    if (contentWorld != null && !contentWorld.equals(ContentWorld.PAGE)) {
      StringBuilder sourceWrapped = new StringBuilder();
      if (!contentWorlds.contains(contentWorld)) {
        contentWorlds.add(contentWorld);

        StringBuilder pluginScriptsSource = new StringBuilder();
        LinkedHashSet<PluginScript> pluginScriptsRequired = this.getPluginScriptsRequiredInAllContentWorlds();
        for (PluginScript script : pluginScriptsRequired) {
          pluginScriptsSource.append(script.getSource());
        }
        String contentWorldCreatorCode = CONTENT_WORLDS_GENERATOR_JS_SOURCE()
                .replace(PluginScriptsUtil.VAR_CONTENT_WORLD_NAME_ARRAY, "'" + escapeContentWorldName(contentWorld.getName()) + "'")
                .replace(PluginScriptsUtil.VAR_JSON_SOURCE_ENCODED, escapeCode(pluginScriptsSource.toString()));
        sourceWrapped.append(contentWorldCreatorCode).append(";");
      }
      return sourceWrapped.append(wrapSourceCodeInContentWorld(contentWorld, source)).toString();
    }
    return source;
  }

  public String wrapSourceCodeInContentWorld(@Nullable ContentWorld contentWorld, String source) {
    String sourceWrapped = contentWorld == null || contentWorld.equals(ContentWorld.PAGE) ? source :
            CONTENT_WORLD_WRAPPER_JS_SOURCE()
                    .replace(PluginScriptsUtil.VAR_CONTENT_WORLD_NAME, escapeContentWorldName(contentWorld.getName()))
                    .replace(PluginScriptsUtil.VAR_JSON_SOURCE_ENCODED, escapeCode(source));

    return sourceWrapped;
  }

  public static String escapeCode(String code) {
    String escapedCode = JSONObject.quote(code);
    // escapedCode = escapedCode.substring(1, escapedCode.length() - 1);
    return escapedCode;
  }

  public static String escapeContentWorldName(String name) {
    return name.replaceAll("'", "\\\\'");
  }

  public LinkedHashSet<UserScript> getUserOnlyScriptsAt(UserScriptInjectionTime injectionTime) {
    return new LinkedHashSet<>(this.userOnlyScripts.get(injectionTime));
  }

  private void updateContentWorldsCreatorScript() {
    String source = generateContentWorldsCreatorCode();
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      if (contentWorldsCreatorScript != null) {
        contentWorldsCreatorScript.remove();
      }
      if (!source.isEmpty() && webView != null) {
        contentWorldsCreatorScript = WebViewCompat.addDocumentStartJavaScript(
                webView,
                source,
                new HashSet<String>() {{
                  add("*");
                }}
        );
      }
    }
  }

  public boolean addUserOnlyScript(UserScript userOnlyScript) {
    final LinkedHashSet<UserScript> scripts =
            this.userOnlyScripts.get(userOnlyScript.getInjectionTime());
    if (scripts.contains(userOnlyScript)) {
      return false;
    }
    ContentWorld contentWorld = userOnlyScript.getContentWorld();
    if (contentWorld != null) {
      contentWorlds.add(contentWorld);
    }
    this.updateContentWorldsCreatorScript();
    if (webView != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      String source = userOnlyScript.getSource();
      if (userOnlyScript.getInjectionTime() == UserScriptInjectionTime.AT_DOCUMENT_END) {
        source = "if (document.readyState === 'complete') { " + source + "} else { window.addEventListener('load', function() { " + source + " }); }";
      }
      source = wrapSourceCodeAddChecks(source, userOnlyScript);

      try {
        ScriptHandler scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                wrapSourceCodeInContentWorld(userOnlyScript.getContentWorld(), source),
                userOnlyScript.getAllowedOriginRules()
        );
        this.scriptHandlerMap.put(userOnlyScript, scriptHandler);
        this.userOnlyScriptRegistrationErrors.remove(userOnlyScript);
      } catch (RuntimeException e) {
        retryUserOnlyScriptAfterStartup(userOnlyScript, source, e);
      }
    }
    return scripts.add(userOnlyScript);
  }

  private void retryUserOnlyScriptAfterStartup(
          final UserScript userOnlyScript,
          final String source,
          @NonNull RuntimeException originalError
  ) {
    final WebView expectedWebView = webView;
    if (!isWebViewStartupInProgressError(originalError) || expectedWebView == null) {
      userOnlyScriptRegistrationErrors.put(userOnlyScript, originalError);
      notifyScriptRegistrationsCompleteIfReady();
      Log.e(LOG_TAG, "addDocumentStartJavaScript failed for "
              + userOnlyScript.getGroupName(), originalError);
      return;
    }
    final Object token = new Object();
    pendingUserOnlyScriptRegistrations.put(userOnlyScript, token);
    WebViewStartupCoordinator.ensureStarted(
            expectedWebView.getContext(),
            new WebViewStartupCoordinator.Callback() {
              @Override
              public void onSuccess() {
                expectedWebView.post(() -> {
                  if (webView != expectedWebView
                          || pendingUserOnlyScriptRegistrations.get(userOnlyScript) != token
                          || !userOnlyScripts.get(userOnlyScript.getInjectionTime()).contains(userOnlyScript)) {
                    return;
                  }
                  try {
                    ScriptHandler scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                            expectedWebView,
                            wrapSourceCodeInContentWorld(userOnlyScript.getContentWorld(), source),
                            userOnlyScript.getAllowedOriginRules()
                    );
                    scriptHandlerMap.put(userOnlyScript, scriptHandler);
                    userOnlyScriptRegistrationErrors.remove(userOnlyScript);
                  } catch (RuntimeException retryError) {
                    userOnlyScriptRegistrationErrors.put(userOnlyScript, retryError);
                    Log.e(LOG_TAG, "retry addDocumentStartJavaScript failed for "
                            + userOnlyScript.getGroupName(), retryError);
                  } finally {
                    if (pendingUserOnlyScriptRegistrations.get(userOnlyScript) == token) {
                      pendingUserOnlyScriptRegistrations.remove(userOnlyScript);
                    }
                    notifyScriptRegistrationsCompleteIfReady();
                  }
                });
              }

              @Override
              public void onError(@NonNull Throwable error) {
                if (pendingUserOnlyScriptRegistrations.get(userOnlyScript) != token) {
                  return;
                }
                pendingUserOnlyScriptRegistrations.remove(userOnlyScript);
                userOnlyScriptRegistrationErrors.put(userOnlyScript, error);
                notifyScriptRegistrationsCompleteIfReady();
                Log.e(LOG_TAG, "WebView startup failed before retrying user script "
                        + userOnlyScript.getGroupName(), error);
              }
            }
    );
  }

  public void addUserOnlyScripts(List<UserScript> userOnlyScripts) {
    for (UserScript userOnlyScript : userOnlyScripts) {
      this.addUserOnlyScript(userOnlyScript);
    }
  }

  public boolean removeUserOnlyScript(UserScript userOnlyScript) {
    this.pendingUserOnlyScriptRegistrations.remove(userOnlyScript);
    this.userOnlyScriptRegistrationErrors.remove(userOnlyScript);
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      ScriptHandler scriptHandler = this.scriptHandlerMap.get(userOnlyScript);
      if (scriptHandler != null) {
        scriptHandler.remove();
        this.scriptHandlerMap.remove(userOnlyScript);
      }
      this.updateContentWorldsCreatorScript();
    }
    boolean removed = this.userOnlyScripts.get(userOnlyScript.getInjectionTime()).remove(userOnlyScript);
    notifyScriptRegistrationsCompleteIfReady();
    return removed;
  }

  public boolean removeUserOnlyScriptAt(int index, UserScriptInjectionTime injectionTime) {
    UserScript userOnlyScript = new ArrayList<>(this.userOnlyScripts.get(injectionTime)).get(index);
    return this.removeUserOnlyScript(userOnlyScript);
  }

  public void removeAllUserOnlyScripts() {
    this.pendingUserOnlyScriptRegistrations.clear();
    this.userOnlyScriptRegistrationErrors.clear();
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      for (UserScript userOnlyScript : this.userOnlyScripts.get(UserScriptInjectionTime.AT_DOCUMENT_START)) {
        ScriptHandler scriptHandler = this.scriptHandlerMap.get(userOnlyScript);
        if (scriptHandler != null) {
          scriptHandler.remove();
          this.scriptHandlerMap.remove(userOnlyScript);
        }
      }
      for (UserScript userOnlyScript : this.userOnlyScripts.get(UserScriptInjectionTime.AT_DOCUMENT_END)) {
        ScriptHandler scriptHandler = this.scriptHandlerMap.get(userOnlyScript);
        if (scriptHandler != null) {
          scriptHandler.remove();
          this.scriptHandlerMap.remove(userOnlyScript);
        }
      }
    }
    this.userOnlyScripts.get(UserScriptInjectionTime.AT_DOCUMENT_START).clear();
    this.userOnlyScripts.get(UserScriptInjectionTime.AT_DOCUMENT_END).clear();
    notifyScriptRegistrationsCompleteIfReady();
  }

  public LinkedHashSet<PluginScript> getPluginScriptsAt(UserScriptInjectionTime injectionTime) {
    return new LinkedHashSet<>(this.pluginScripts.get(injectionTime));
  }

  public LinkedHashSet<PluginScript> getPluginScriptsRequiredInAllContentWorlds() {
    LinkedHashSet<PluginScript> pluginScriptsRequired = new LinkedHashSet<>();
    LinkedHashSet<PluginScript> scripts = this.getPluginScriptsAt(UserScriptInjectionTime.AT_DOCUMENT_START);
    for (PluginScript script : scripts) {
      if (script.isRequiredInAllContentWorlds()) {
        pluginScriptsRequired.add(script);
      }
    }
    return pluginScriptsRequired;
  }

  public boolean addPluginScript(final PluginScript pluginScript) {
    final LinkedHashSet<PluginScript> scripts =
            this.pluginScripts.get(pluginScript.getInjectionTime());
    if (scripts.contains(pluginScript)) {
      return false;
    }
    ContentWorld contentWorld = pluginScript.getContentWorld();
    if (contentWorld != null) {
      contentWorlds.add(contentWorld);
    }
    this.updateContentWorldsCreatorScript();
    if (webView != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      String source = pluginScript.getSource();
      if (pluginScript.getInjectionTime() == UserScriptInjectionTime.AT_DOCUMENT_END) {
        source = "if (document.readyState === 'complete') { " + source + "} else { window.addEventListener('load', function() { " + source + " }); }";
      }
      source = wrapSourceCodeAddChecks(source, pluginScript);
      final String finalSource = wrapSourceCodeInContentWorld(pluginScript.getContentWorld(), source);
      final Object registrationToken = new Object();
      pendingPluginScriptRegistrations.put(pluginScript, registrationToken);

      // Defer WebViewCompat.addDocumentStartJavaScript to the next UI-thread
      // message. Its binder IPC to the Chromium renderer process must not run
      // while FlutterWebView is still synchronously building the platform view.
      // InAppWebView.prepareAndAddUserScripts() issues 5 to 9 of these
      // registrations synchronously when the JS bridge is enabled (PromisePolyfill,
      // JS Bridge, Print, OnWindowBlur, OnWindowFocus, plus up to four more
      // depending on settings); running them inline was observed to leave
      // onWebViewCreated never fired on ~50% of release-build cold starts on real
      // Android devices, with the failure toggling deterministically across kill-
      // relaunch cycles. Debug builds were not affected.
      webView.post(new Runnable() {
        @Override
        public void run() {
          if (webView != null
                  && pendingPluginScriptRegistrations.get(pluginScript) == registrationToken
                  && pluginScripts.get(pluginScript.getInjectionTime()).contains(pluginScript)) {
            try {
              ScriptHandler scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                      webView,
                      finalSource,
                      pluginScript.getAllowedOriginRules()
              );
              scriptHandlerMap.put(pluginScript, scriptHandler);
              pluginScriptRegistrationErrors.remove(pluginScript);
              if (pendingPluginScriptRegistrations.get(pluginScript) == registrationToken) {
                pendingPluginScriptRegistrations.remove(pluginScript);
              }
              notifyScriptRegistrationsCompleteIfReady();
            } catch (RuntimeException e) {
              retryPluginScriptAfterStartup(
                      pluginScript,
                      finalSource,
                      registrationToken,
                      e
              );
            }
          }
        }
      });
    }
    return scripts.add(pluginScript);
  }

  private void retryPluginScriptAfterStartup(
          final PluginScript pluginScript,
          final String source,
          final Object registrationToken,
          @NonNull RuntimeException originalError
  ) {
    final WebView expectedWebView = webView;
    if (!isWebViewStartupInProgressError(originalError) || expectedWebView == null) {
      if (pendingPluginScriptRegistrations.get(pluginScript) == registrationToken) {
        pendingPluginScriptRegistrations.remove(pluginScript);
      }
      pluginScriptRegistrationErrors.put(pluginScript, originalError);
      notifyScriptRegistrationsCompleteIfReady();
      Log.e(LOG_TAG, "addDocumentStartJavaScript failed for plugin script "
              + pluginScript.getGroupName(), originalError);
      return;
    }
    WebViewStartupCoordinator.ensureStarted(
            expectedWebView.getContext(),
            new WebViewStartupCoordinator.Callback() {
              @Override
              public void onSuccess() {
                expectedWebView.post(() -> {
                  if (webView != expectedWebView
                          || pendingPluginScriptRegistrations.get(pluginScript) != registrationToken
                          || !pluginScripts.get(pluginScript.getInjectionTime()).contains(pluginScript)) {
                    return;
                  }
                  try {
                    ScriptHandler scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                            expectedWebView,
                            source,
                            pluginScript.getAllowedOriginRules()
                    );
                    scriptHandlerMap.put(pluginScript, scriptHandler);
                    pluginScriptRegistrationErrors.remove(pluginScript);
                  } catch (RuntimeException retryError) {
                    pluginScriptRegistrationErrors.put(pluginScript, retryError);
                    Log.e(LOG_TAG, "retry addDocumentStartJavaScript failed for plugin script "
                            + pluginScript.getGroupName(), retryError);
                  } finally {
                    if (pendingPluginScriptRegistrations.get(pluginScript) == registrationToken) {
                      pendingPluginScriptRegistrations.remove(pluginScript);
                    }
                    notifyScriptRegistrationsCompleteIfReady();
                  }
                });
              }

              @Override
              public void onError(@NonNull Throwable error) {
                if (pendingPluginScriptRegistrations.get(pluginScript) != registrationToken) {
                  return;
                }
                pendingPluginScriptRegistrations.remove(pluginScript);
                pluginScriptRegistrationErrors.put(pluginScript, error);
                notifyScriptRegistrationsCompleteIfReady();
                Log.e(LOG_TAG, "WebView startup failed before retrying plugin script "
                        + pluginScript.getGroupName(), error);
              }
            }
    );
  }

  private boolean isWebViewStartupInProgressError(@NonNull Throwable error) {
    Throwable current = error;
    while (current != null) {
      String message = current.getMessage();
      if (message != null && message.contains("Must be started before we block")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  public void addPluginScripts(List<PluginScript> pluginScripts) {
    for (PluginScript pluginScript : pluginScripts) {
      this.addPluginScript(pluginScript);
    }
  }

  public boolean removePluginScript(PluginScript pluginScript) {
    // Invalidate before removing the installed handler. A queued registration
    // for the same script must not be able to run after this method returns.
    this.pendingPluginScriptRegistrations.remove(pluginScript);
    this.pluginScriptRegistrationErrors.remove(pluginScript);
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      ScriptHandler scriptHandler = this.scriptHandlerMap.get(pluginScript);
      if (scriptHandler != null) {
        scriptHandler.remove();
        this.scriptHandlerMap.remove(pluginScript);
      }
      this.updateContentWorldsCreatorScript();
    }
    boolean removed = this.pluginScripts.get(pluginScript.getInjectionTime()).remove(pluginScript);
    notifyScriptRegistrationsCompleteIfReady();
    return removed;
  }

  public void removeAllPluginScripts() {
    this.pendingPluginScriptRegistrations.clear();
    this.pluginScriptRegistrationErrors.clear();
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
      for (PluginScript pluginScript : this.pluginScripts.get(UserScriptInjectionTime.AT_DOCUMENT_START)) {
        ScriptHandler scriptHandler = this.scriptHandlerMap.get(pluginScript);
        if (scriptHandler != null) {
          scriptHandler.remove();
          this.scriptHandlerMap.remove(pluginScript);
        }
      }
      for (PluginScript pluginScript : this.pluginScripts.get(UserScriptInjectionTime.AT_DOCUMENT_END)) {
        ScriptHandler scriptHandler = this.scriptHandlerMap.get(pluginScript);
        if (scriptHandler != null) {
          scriptHandler.remove();
          this.scriptHandlerMap.remove(pluginScript);
        }
      }
    }
    this.pluginScripts.get(UserScriptInjectionTime.AT_DOCUMENT_START).clear();
    this.pluginScripts.get(UserScriptInjectionTime.AT_DOCUMENT_END).clear();
    notifyScriptRegistrationsCompleteIfReady();
  }

  public void runWhenScriptRegistrationsComplete(@NonNull Runnable callback) {
    if (pendingUserOnlyScriptRegistrations.isEmpty()
            && pendingPluginScriptRegistrations.isEmpty()) {
      callback.run();
      return;
    }
    scriptRegistrationsCompleteCallbacks.add(callback);
  }

  public boolean isDisposed() {
    return disposed;
  }

  @Nullable
  public Throwable getScriptRegistrationError() {
    if (!userOnlyScriptRegistrationErrors.isEmpty()) {
      return userOnlyScriptRegistrationErrors.values().iterator().next();
    }
    if (!pluginScriptRegistrationErrors.isEmpty()) {
      return pluginScriptRegistrationErrors.values().iterator().next();
    }
    return null;
  }

  private void notifyScriptRegistrationsCompleteIfReady() {
    if (!pendingUserOnlyScriptRegistrations.isEmpty()
            || !pendingPluginScriptRegistrations.isEmpty()) {
      return;
    }
    List<Runnable> callbacks = new ArrayList<>(scriptRegistrationsCompleteCallbacks);
    scriptRegistrationsCompleteCallbacks.clear();
    for (Runnable callback : callbacks) {
      callback.run();
    }
  }

  public LinkedHashSet<UserScript> getUserOnlyScriptAsList() {
    LinkedHashSet<UserScript> userOnlyScripts = new LinkedHashSet<>();
    Collection<LinkedHashSet<UserScript>> collection = this.userOnlyScripts.values();
    for (LinkedHashSet<UserScript> list : collection) {
      userOnlyScripts.addAll(list);
    }
    return userOnlyScripts;
  }

  public LinkedHashSet<PluginScript> getPluginScriptAsList() {
    LinkedHashSet<PluginScript> pluginScripts = new LinkedHashSet<>();
    Collection<LinkedHashSet<PluginScript>> collection = this.pluginScripts.values();
    for (LinkedHashSet<PluginScript> list : collection) {
      pluginScripts.addAll(list);
    }
    return pluginScripts;
  }

  public void resetContentWorlds() {
    this.contentWorlds.clear();
    this.contentWorlds.add(ContentWorld.PAGE);

    LinkedHashSet<PluginScript> pluginScripts = this.getPluginScriptAsList();
    for (PluginScript pluginScript : pluginScripts) {
      ContentWorld contentWorld = pluginScript.getContentWorld();
      this.contentWorlds.add(contentWorld);
    }

    LinkedHashSet<UserScript> userOnlyScripts = this.getUserOnlyScriptAsList();
    for (UserScript userOnlyScript : userOnlyScripts) {
      ContentWorld contentWorld = userOnlyScript.getContentWorld();
      this.contentWorlds.add(contentWorld);
    }
  }

  public boolean containsPluginScript(PluginScript pluginScript) {
    return this.getPluginScriptAsList().contains(pluginScript);
  }

  public boolean containsPluginScriptByGroupName(String groupName) {
    LinkedHashSet<PluginScript> pluginScripts = this.getPluginScriptAsList();
    for (PluginScript pluginScript : pluginScripts) {
      if (Util.objEquals(groupName, pluginScript.getGroupName())) {
        return true;
      }
    }
    return false;
  }

  public boolean containsUserOnlyScript(UserScript userOnlyScript) {
    return this.getUserOnlyScriptAsList().contains(userOnlyScript);
  }

  public boolean containsUserOnlyScriptByGroupName(String groupName) {
    LinkedHashSet<UserScript> userOnlyScripts = this.getUserOnlyScriptAsList();
    for (UserScript userOnlyScript : userOnlyScripts) {
      if (Util.objEquals(groupName, userOnlyScript.getGroupName())) {
        return true;
      }
    }

    return false;
  }

  public void removePluginScriptsByGroupName(String groupName) {
    LinkedHashSet<PluginScript> pluginScripts = this.getPluginScriptAsList();
    for (PluginScript pluginScript : pluginScripts) {
      if (Util.objEquals(groupName, pluginScript.getGroupName())) {
        this.removePluginScript(pluginScript);
      }
    }
  }

  public void removeUserOnlyScriptsByGroupName(String groupName) {
    LinkedHashSet<UserScript> userOnlyScripts = this.getUserOnlyScriptAsList();
    for (UserScript userOnlyScript : userOnlyScripts) {
      if (Util.objEquals(groupName, userOnlyScript.getGroupName())) {
        this.removeUserOnlyScript(userOnlyScript);
      }
    }
  }

  @NonNull
  public LinkedHashSet<ContentWorld> getContentWorlds() {
    return new LinkedHashSet<>(this.contentWorlds);
  }

  static private String wrapSourceCodeAddChecks(String source, UserScript userScript) {
    StringBuilder ifStatement = new StringBuilder("if (");
    Set<String> allowedOriginRules = userScript.getAllowedOriginRules();
    boolean forMainFrameOnly = userScript.isForMainFrameOnly();
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !allowedOriginRules.contains("*")) {
      if (allowedOriginRules.isEmpty()) {
        // return empty source string if allowedOriginRules is an empty list.
        // an empty list means that this UserScript is not allowed for any origin.
        return "";
      }
      StringBuilder jsRegExpArray = new StringBuilder("[");
      for (String allowedOriginRule : allowedOriginRules) {
        if (jsRegExpArray.length() > 1) {
          jsRegExpArray.append(", ");
        }
        jsRegExpArray.append("new RegExp(").append(UserContentController.escapeCode(allowedOriginRule)).append(")");
      }
      if (jsRegExpArray.length() > 1) {
        jsRegExpArray.append("]");
        ifStatement.append(jsRegExpArray).append(".some(function(rx) { return rx.test(window.location.origin); })");
      }
    }
    if (forMainFrameOnly) {
      if (ifStatement.length() > 4) {
        ifStatement.append(" && ");
      }
      ifStatement.append("window === window.top");
    }
    return ifStatement.length() > 4 ? ifStatement.append(") {").append(source).append("}").toString() : source;
  }

  private static String USER_SCRIPTS_AT_DOCUMENT_START_WRAPPER_JS_SOURCE() {
    return "if (window._" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_userScriptsAtDocumentStartLoaded == null || !window._" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_userScriptsAtDocumentStartLoaded) {" +
            "  window._" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_userScriptsAtDocumentStartLoaded = true;" +
            "  " + PluginScriptsUtil.VAR_PLACEHOLDER_VALUE +
            "}";
  }

  private static String USER_SCRIPTS_AT_DOCUMENT_END_WRAPPER_JS_SOURCE() {
      return "if (window._" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_userScriptsAtDocumentEndLoaded == null || !window._" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_userScriptsAtDocumentEndLoaded) {" +
              "  window._" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_userScriptsAtDocumentEndLoaded = true;" +
              "  " + PluginScriptsUtil.VAR_PLACEHOLDER_VALUE +
              "}";
    }

  private static String CONTENT_WORLDS_GENERATOR_JS_SOURCE() {
        return "(function() {" +
                "  var interval = setInterval(function() {" +
                "    if (document.body == null) {return;}" +
                "    var contentWorldNames = [" + PluginScriptsUtil.VAR_CONTENT_WORLD_NAME_ARRAY + "];" +
                "    for (var contentWorldName of contentWorldNames) {" +
                "      var iframeId = '" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_' + contentWorldName;" +
                "      var iframe = document.getElementById(iframeId);" +
                "      if (iframe == null) {" +
                "        iframe = document.createElement('iframe');" +
                "        iframe.id = iframeId;" +
                "        iframe.style = 'display: none; z-index: 0; position: absolute; width: 0px; height: 0px';" +
                "        document.body.append(iframe);" +
                "      }" +
                "      if (iframe.contentWindow.document.getElementById('" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_plugin_scripts') == null) {" +
                "        var script = iframe.contentWindow.document.createElement('script');" +
                "        script.id = '" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_plugin_scripts';" +
                "        script.innerHTML = " + PluginScriptsUtil.VAR_JSON_SOURCE_ENCODED + ";" +
                "        iframe.contentWindow.document.body.append(script);" +
                "      }" +
                "    }" +
                "    clearInterval(interval);" +
                "  });" +
                "})();";
      }

  private static String CONTENT_WORLD_WRAPPER_JS_SOURCE() {
          return "(function() {" +
                  "  var interval = setInterval(function() {" +
                  "    if (document.body == null) {return;}" +
                  "    var iframeId = '" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_" + PluginScriptsUtil.VAR_CONTENT_WORLD_NAME + "';" +
                  "    var iframe = document.getElementById(iframeId);" +
                  "    if (iframe == null) {" +
                  "      iframe = document.createElement('iframe');" +
                  "      iframe.id = iframeId;" +
                  "      iframe.style = 'display: none; z-index: 0; position: absolute; width: 0px; height: 0px';" +
                  "      document.body.append(iframe);" +
                  "    }" +
                  "    if (iframe.contentWindow.document.querySelector('#" + JavaScriptBridgeJS.get_JAVASCRIPT_BRIDGE_NAME() + "_plugin_scripts') == null) {" +
                  "      return;" +
                  "    }" +
                  "    var script = iframe.contentWindow.document.createElement('script');" +
                  "    script.innerHTML = " + PluginScriptsUtil.VAR_JSON_SOURCE_ENCODED + ";" +
                  "    iframe.contentWindow.document.body.append(script);" +
                  "    clearInterval(interval);" +
                  "  });" +
                  "})();";
        }

  private static final String DOCUMENT_READY_WRAPPER_JS_SOURCE = "if (document.readyState === 'interactive' || document.readyState === 'complete') { " +
          "  " + PluginScriptsUtil.VAR_PLACEHOLDER_VALUE +
          "}";

  @Override
  public void dispose() {
    disposed = true;
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && contentWorldsCreatorScript != null) {
      contentWorldsCreatorScript.remove();
    }
    removeAllUserOnlyScripts();
    removeAllPluginScripts();
    webView = null;
  }
}
