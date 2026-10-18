/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package com.pichillilorenzo.flutter_inappwebview_android.headless_in_app_webview;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import com.pichillilorenzo.flutter_inappwebview_android.InAppWebViewFlutterPlugin;
import com.pichillilorenzo.flutter_inappwebview_android.types.ChannelDelegateImpl;
import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.FlutterWebView;

import com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview.InAppWebView;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class HeadlessInAppWebViewManager extends ChannelDelegateImpl {
  protected static final String LOG_TAG = "HeadlessInAppWebViewManager";
  public static final String METHOD_CHANNEL_NAME = "com.pichillilorenzo/flutter_headless_inappwebview";
  
  private final Map<String, InAppWebView.RuntimeActivityIdentity> runtimeTargets = new java.util.LinkedHashMap<>();
  public final Map<String, HeadlessInAppWebView> webViews = new HashMap<>();
  @Nullable
  public InAppWebViewFlutterPlugin plugin;

  public HeadlessInAppWebViewManager(final InAppWebViewFlutterPlugin plugin) {
    super(new MethodChannel(plugin.messenger, METHOD_CHANNEL_NAME));
    this.plugin = plugin;
  }

  @Override
  public void onMethodCall(final MethodCall call, @NonNull final Result result) {
    final String id = (String) call.argument("id");

    switch (call.method) {
      case "stopRuntimeActivity": {
        InAppWebView.RuntimeActivityIdentity reference = runtimeTargets.get(id);
        if (reference == null) { result.success(false); break; }
        InAppWebView target = reference.view.get();
        if (target == null) { result.success(reference.stopConfirmed); break; }
        target.stopRuntimeActivity(new Result() {
          public void success(Object value) { result.success(true); }
          public void error(String code, String message, Object details) { result.error(code, message, details); }
          public void notImplemented() { result.notImplemented(); }
        });
        break;
      }
      case "run":
        {
          HashMap<String, Object> params = (HashMap<String, Object>) call.argument("params");
          run(id, params);
        }
        result.success(true);
        break;
      default:
        result.notImplemented();
    }
  }

  public void run(String id, HashMap<String, Object> params) {
    if (plugin == null || (plugin.activity == null && plugin.applicationContext == null)) return;
    Context context = plugin.activity;
    if (context == null) {
      context = plugin.applicationContext;
    }
    FlutterWebView flutterWebView = new FlutterWebView(plugin, context, id, params);
    // Bound deallocated identity tombstones; live targets remain discoverable
    // through a pending native handoff without being retained by this registry.
    if (runtimeTargets.size() >= 256) {
      java.util.Iterator<java.util.Map.Entry<String, InAppWebView.RuntimeActivityIdentity>> entries = runtimeTargets.entrySet().iterator();
      while (entries.hasNext() && runtimeTargets.size() >= 256) {
        if (entries.next().getValue().view.get() == null) entries.remove();
      }
    }
    if (flutterWebView.webView != null) runtimeTargets.put(id, flutterWebView.webView.runtimeActivityIdentity());
    HeadlessInAppWebView headlessInAppWebView = new HeadlessInAppWebView(plugin, id, flutterWebView);
    webViews.put(id, headlessInAppWebView);
    
    headlessInAppWebView.prepare(params);
    headlessInAppWebView.onWebViewCreated();
    flutterWebView.makeInitialLoad(params);
  }

  @Override
  public void dispose() {
    super.dispose();
    Collection<HeadlessInAppWebView> headlessInAppWebViews = webViews.values();
    for (HeadlessInAppWebView headlessInAppWebView : headlessInAppWebViews) {
      if (headlessInAppWebView != null) {
        headlessInAppWebView.dispose();
      }
    }
    webViews.clear();
    runtimeTargets.clear();
    plugin = null;
  }
}
