# Per-instance runtime stop

Android and iOS expose an irreversible `InAppWebViewController.stopRuntimeActivity()`.
It disables JavaScript and replaces the old document with a blank document, waiting
for that navigation's completion (five-second timeout). It retains the native view
and Flutter command channels for a later final dispose. A timeout/error remains a
failure and can retry; parallel requests on one native instance share one unload.
No Android process-wide `pauseTimers`, shared cookie/cache clearing, or renderer
process termination is performed. Late entry navigation/reload cannot restart the
stopped instance. Do not reuse that WebView for another runtime.

For a headless/presentation handoff, hosts may stop through either exact identity:

- `HeadlessInAppWebView.stopRuntimeActivity(): Future<bool>` uses the original
  headless native id, including after native transfer consumed its instance channel.
- `InAppWebViewController.stopKeepAliveRuntimeActivity(keepAlive): Future<bool>`
  uses the retained presentation id.

`true` confirms completion for that exact native target (or a known target whose earlier stop or final disposal was confirmed). `false` means the candidate was not found and does not establish
that another candidate stopped. A host using both must require at least one
positive confirmation. Native headless identity registries hold weak references plus a confirmed-stop flag; collection alone is not release evidence;
registration prunes deallocated identity tombstones above 256, while live native
objects remain discoverable. Unknown/evicted ids return false.

The host must first revoke business ownership, stop its timers, microphone/location
and other native work, and prevent old bridge calls from acquiring new authority.
Blank navigation is document teardown; it is not proof that arbitrary persistent
service workers, shared process memory, or app-owned network services have ended.
It also does not quantify memory or battery improvement.

Final disposal remains required. For an instance explicitly marked by the native
runtime-stop entry, Android removes that exact WebView from its actual parent and
calls `destroy()` synchronously, including after failed blank navigation. Its
terminal stop client handles renderer death by destroying only the old view.
Ordinary Android WebViews preserve the target branch's blank-navigation callback
destruction timing; their manager disposal receipt does not prove destruction has
already completed. iOS native disposal is idempotent. Its Dart
presentation wrapper may release keep-alive presentation first and later dispose
fully: it saves the original channel, unregisters only if that wrapper still owns
the handler, and disposes storage once. Reattachment or runtime channel reclaim
updates ownership so an old wrapper cannot remove its successor's handler.

Regression checks cover transaction coalescing, failure/retry/late completion,
per-instance isolation, native blank acknowledgement, no global pause, final
Android destruction, and Dart channel teardown after presentation handoff.
Robolectric does not execute Chromium, and Dart platform-channel mocks are not
real-device resource/energy measurements.

The iOS implementation emits finite `[EHRuntimeLifecycle]` diagnostics for a new
stop attempt, exact blank navigation completion, failed stop, final dispose and
deallocation. Each contains the native pointer, view id and UIKit application
state, with no URL/document data. Final-dispose/deallocation records are limited
to instances that received runtime stop. Match by process and object lifetime to
avoid pointer-reuse ambiguity. A blank acknowledgement is not final disposal;
neither acknowledgement proves shared WebKit process/memory reclamation. These
records do not add background work or change release behavior.
