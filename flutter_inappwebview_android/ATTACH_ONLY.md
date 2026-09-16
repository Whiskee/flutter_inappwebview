# Exact Android WebView transfer

The existing `InAppWebView` facade and widget creation parameters accept
`attachOnly` (default false), `onAttachStart`, and `onAttachResult(bool attached)`.
These options affect Android only. Existing default-off/iOS behavior is unchanged.

Strict creation requires the exact live headless or keep-alive target. A requested
headless ID cannot fall back to another keep-alive ID, and a claimed or disposed
target cannot be taken. A miss creates an inert platform surface, never a new
WebView, initial load, or replacement keep-alive entry. A valid transfer preserves
the native instance and does not rerun its initial load.

`onAttachStart` marks actual platform-create submission. Dart then queries a
per-platform-view native channel for `isAttached`; the result remains available
until that platform view is disposed, so it does not depend on a listener being
present at native creation. `onAttachResult` runs before ordinary
`onWebViewCreated`; unavailable skips ordinary ready. If Dart detaches during the
query, the result is still reported for owner cleanup, but ready is suppressed.

Use the existing keep-alive key for incidental detach/re-attach. The strict native
presenter claim is released on platform-view disposal. Explicit runtime exit uses
the existing `disposeKeepAlive`. This change does not add physical-destroy
acknowledgement or claim renderer/GC/kernel reclamation. Headless runtime execution
without an Activity remains a separate device validation requirement.

Native tests execute the real factory, headless transfer, presenter disposal and
keep-alive manager; only Android view allocation/resources and channel transport
are substituted. Test-only JUnit 4.13.2 and Mockito 5.4.0 are used; production
dependencies are unchanged.
