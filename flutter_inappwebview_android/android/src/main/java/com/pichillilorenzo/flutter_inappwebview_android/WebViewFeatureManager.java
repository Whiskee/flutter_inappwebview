package com.pichillilorenzo.flutter_inappwebview_android;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewFeature;

import com.pichillilorenzo.flutter_inappwebview_android.types.ChannelDelegateImpl;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class WebViewFeatureManager extends ChannelDelegateImpl {
  protected static final String LOG_TAG = "WebViewFeatureManager";
  public static final String METHOD_CHANNEL_NAME = "com.pichillilorenzo/flutter_inappwebview_webviewfeature";

  @Nullable
  public InAppWebViewFlutterPlugin plugin;

  public WebViewFeatureManager(@NonNull final InAppWebViewFlutterPlugin plugin) {
    super(new MethodChannel(plugin.messenger, METHOD_CHANNEL_NAME));
    this.plugin = plugin;
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    switch (call.method) {
      case "isFeatureSupported":
        String feature = (String) call.argument("feature");
        result.success(WebViewFeature.isFeatureSupported(feature));
        break;
      case "isStartupFeatureSupported":
        if (plugin != null && plugin.activity != null) {
          String startupFeature = (String) call.argument("startupFeature");
          result.success(WebViewFeature.isStartupFeatureSupported(plugin.activity, startupFeature));
        }
        break;
      case "startUpWebView":
        if (plugin == null) {
          result.error(LOG_TAG, "Plugin is detached", null);
          break;
        }
        WebViewStartupCoordinator.ensureStarted(
                plugin.applicationContext,
                new WebViewStartupCoordinator.Callback() {
                  @Override
                  public void onSuccess() {
                    result.success(null);
                  }

                  @Override
                  public void onError(@NonNull Throwable error) {
                    result.error(LOG_TAG, error.getMessage(), null);
                  }
                }
        );
        break;
      default:
        result.notImplemented();
    }
  }

  @Override
  public void dispose() {
    super.dispose();
    plugin = null;
  }
}
