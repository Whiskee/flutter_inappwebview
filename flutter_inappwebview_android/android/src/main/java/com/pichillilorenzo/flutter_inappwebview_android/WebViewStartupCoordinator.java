package com.pichillilorenzo.flutter_inappwebview_android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coordinates the one-time asynchronous Android WebView engine startup. */
public final class WebViewStartupCoordinator {
  public interface Callback {
    void onSuccess();

    void onError(@NonNull Throwable error);
  }

  private enum State {
    NOT_STARTED,
    STARTING,
    STARTED,
    FAILED
  }

  private static final Object LOCK = new Object();
  private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
  private static final ExecutorService BACKGROUND_EXECUTOR =
          Executors.newSingleThreadExecutor();
  private static final List<Callback> PENDING_CALLBACKS = new ArrayList<>();

  private static State state = State.NOT_STARTED;
  private static Throwable startupError;

  private WebViewStartupCoordinator() {}

  /** Always enqueue, even on the UI thread, without waiting for View attachment.
   * Callers must check their owner is still alive when the task runs. */
  public static void postOnMain(@NonNull Runnable runnable) {
    MAIN_HANDLER.post(runnable);
  }

  public static void ensureStarted(
          @NonNull Context context,
          @NonNull Callback callback
  ) {
    final State currentState;
    final Throwable currentError;
    boolean shouldStart = false;
    synchronized (LOCK) {
      currentState = state;
      currentError = startupError;
      if (currentState == State.STARTED || currentState == State.FAILED) {
        // Deliver the cached terminal result outside the lock.
      } else {
        PENDING_CALLBACKS.add(callback);
        if (currentState == State.NOT_STARTED) {
          state = State.STARTING;
          shouldStart = true;
        }
      }
    }

    if (currentState == State.STARTED) {
      runOnMain(callback::onSuccess);
      return;
    }
    if (currentState == State.FAILED) {
      final Throwable error = currentError != null
              ? currentError
              : new IllegalStateException("Android WebView startup failed");
      runOnMain(() -> callback.onError(error));
      return;
    }
    if (!shouldStart) {
      return;
    }

    try {
      WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(BACKGROUND_EXECUTOR)
              .build();
      WebViewCompat.startUpWebView(
              context.getApplicationContext(),
              config,
              new WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>() {
                @Override
                public void onResult(@NonNull WebViewStartUpResult result) {
                  complete(null);
                }

                @Override
                public void onError(@NonNull WebViewStartupException error) {
                  complete(error);
                }
              }
      );
    } catch (RuntimeException error) {
      complete(error);
    }
  }

  private static void complete(Throwable error) {
    final List<Callback> callbacks;
    synchronized (LOCK) {
      state = error == null ? State.STARTED : State.FAILED;
      startupError = error;
      callbacks = new ArrayList<>(PENDING_CALLBACKS);
      PENDING_CALLBACKS.clear();
    }
    runOnMain(() -> {
      for (Callback callback : callbacks) {
        if (error == null) {
          callback.onSuccess();
        } else {
          callback.onError(error);
        }
      }
    });
  }

  private static void runOnMain(@NonNull Runnable runnable) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      runnable.run();
    } else {
      MAIN_HANDLER.post(runnable);
    }
  }
}
