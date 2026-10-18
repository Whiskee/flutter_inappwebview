package com.pichillilorenzo.flutter_inappwebview_android.webview.in_app_webview;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RuntimeActivityStopperTest {
  static class NativeDocument implements RuntimeActivityStopper.Target {
    final List<RuntimeActivityStopper.Completion> attempts = new ArrayList<>();
    public void unload(RuntimeActivityStopper.Completion callback) { attempts.add(callback); }
  }
  @Test public void concurrentStopsWaitForOneUnloadAndBecomeIdempotent() {
    NativeDocument document = new NativeDocument();
    RuntimeActivityStopper stop = new RuntimeActivityStopper(document);
    List<String> replies = new ArrayList<>();
    stop.stop(error -> replies.add(error));
    stop.stop(error -> replies.add(error));
    assertEquals(1, document.attempts.size());
    assertTrue(replies.isEmpty());
    document.attempts.get(0).complete(null);
    assertEquals(2, replies.size());
    assertNull(replies.get(0));
    stop.stop(error -> replies.add(error));
    assertEquals(3, replies.size());
    assertEquals(1, document.attempts.size());
  }
  @Test public void timeoutRetriesTheNativeUnloadAndLateOldResultCannotCompleteRetry() {
    NativeDocument document = new NativeDocument();
    RuntimeActivityStopper stop = new RuntimeActivityStopper(document);
    List<String> replies = new ArrayList<>();
    stop.stop(replies::add);
    document.attempts.get(0).complete("timeout");
    assertEquals("timeout", replies.get(0));
    stop.stop(replies::add);
    assertEquals(2, document.attempts.size());
    document.attempts.get(0).complete(null);
    assertEquals(1, replies.size());
    document.attempts.get(1).complete(null);
    assertEquals(2, replies.size());
    assertNull(replies.get(1));
  }
  @Test public void oldRuntimeStopDoesNotTouchSuccessor() {
    NativeDocument old = new NativeDocument(), next = new NativeDocument();
    RuntimeActivityStopper first = new RuntimeActivityStopper(old);
    RuntimeActivityStopper second = new RuntimeActivityStopper(next);
    first.stop(error -> {});
    assertEquals(1, old.attempts.size());
    assertTrue(next.attempts.isEmpty());
    second.stop(error -> {});
    old.attempts.get(0).complete(null);
    assertEquals(1, next.attempts.size());
  }
  @Test public void finalNativeDisposalSettlesPendingStopWithoutAnotherUnload() {
    NativeDocument document = new NativeDocument();
    RuntimeActivityStopper stop = new RuntimeActivityStopper(document);
    List<String> replies = new ArrayList<>();
    stop.stop(replies::add);
    stop.disposed();
    stop.stop(replies::add);
    document.attempts.get(0).complete("late timeout");
    assertEquals(2, replies.size());
    assertNull(replies.get(0));
    assertNull(replies.get(1));
    assertEquals(1, document.attempts.size());
  }
  @Test public void synchronousNativeFailureIsRetryable() {
    final int[] calls = {0};
    RuntimeActivityStopper stop = new RuntimeActivityStopper(callback -> {
      if (++calls[0] == 1) throw new IllegalStateException("native failure");
      callback.complete(null);
    });
    List<String> replies = new ArrayList<>();
    stop.stop(replies::add);
    assertTrue(replies.get(0).contains("native failure"));
    stop.stop(replies::add);
    assertNull(replies.get(1));
    assertEquals(2, calls[0]);
  }
}
