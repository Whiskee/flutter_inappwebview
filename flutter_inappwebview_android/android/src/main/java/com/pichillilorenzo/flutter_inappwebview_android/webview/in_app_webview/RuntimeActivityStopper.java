package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;

import java.util.ArrayList;
import java.util.List;

/** Per-native-instance stop transaction. A failed or timed-out attempt may be retried. */
public final class RuntimeActivityStopper {
  public interface Completion { void complete(String error); }
  public interface Target { void unload(Completion completion); }
  private final Target target;
  private final List<Completion> waiters = new ArrayList<>();
  private boolean stopped;
  private boolean disposed;
  private boolean inFlight;
  private int attempt;

  public RuntimeActivityStopper(Target target) { this.target = target; }

  public void stop(Completion completion) {
    if (disposed || stopped) { completion.complete(null); return; }
    waiters.add(completion);
    if (inFlight) return;
    inFlight = true;
    final int capturedAttempt = ++attempt;
    try {
      target.unload(error -> {
        if (capturedAttempt != attempt || !inFlight) return;
        inFlight = false;
        stopped = error == null;
        List<Completion> pending = new ArrayList<>(waiters);
        waiters.clear();
        for (Completion waiter : pending) waiter.complete(error);
      });
    } catch (Exception error) {
      finishFailure(capturedAttempt, error.toString());
    }
  }

  private void finishFailure(int capturedAttempt, String error) {
    if (capturedAttempt != attempt || !inFlight) return;
    inFlight = false;
    List<Completion> pending = new ArrayList<>(waiters);
    waiters.clear();
    for (Completion waiter : pending) waiter.complete(error);
  }

  public void disposed() {
    disposed = true;
    attempt++;
    inFlight = false;
    List<Completion> pending = new ArrayList<>(waiters);
    waiters.clear();
    for (Completion waiter : pending) waiter.complete(null);
  }
}
