import Foundation
import WebKit

/// A terminal, per-WebView unload. Keeping the command channel permits a later
/// foreground dispose; no app-wide timer, cookie or process pool is modified.
final class RuntimeActivityStopper: NSObject, WKNavigationDelegate {
    private weak var webView: InAppWebView?
    private var navigation: WKNavigation?
    private var waiters: [(Error?) -> Void] = []
    private var timeout: DispatchWorkItem?
    private var stopped = false
    private var attempt = 0

    init(webView: InAppWebView) { self.webView = webView }

    func stop(_ completion: @escaping (Error?) -> Void) {
        if stopped { completion(nil); return }
        waiters.append(completion)
        if waiters.count > 1 { return }
        guard let webView = webView else { finish(nil); return }
        attempt += 1
        let capturedAttempt = attempt
        webView.logRuntimeLifecycle("native_stop_begin")
        webView.stopLoading()
        // Release a previous pauseTimers alert before unloading its document.
        webView.resumeTimers()
        webView.configuration.preferences.javaScriptEnabled = false
        if #available(iOS 14.0, *) {
            webView.configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        }
        if #available(iOS 15.0, *) {
            webView.setAllMediaPlaybackSuspended(true, completionHandler: {})
            webView.setMicrophoneCaptureState(.none, completionHandler: {})
            webView.setCameraCaptureState(.none, completionHandler: {})
        }
        webView.navigationDelegate = self
        let timer = DispatchWorkItem { [weak self] in
            guard let self = self, self.attempt == capturedAttempt else { return }
            self.finish(NSError(domain: "EvenHubRuntimeStop", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Blank document did not finish within 5 seconds"]))
        }
        timeout = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timer)
        navigation = webView.loadStoppedBlankDocument()
        if navigation == nil {
            finish(NSError(domain: "EvenHubRuntimeStop", code: 2,
                userInfo: [NSLocalizedDescriptionKey: "Blank navigation was not created"]))
        }
    }

    private func finish(_ error: Error?) {
        guard !waiters.isEmpty else { return }
        timeout?.cancel()
        timeout = nil
        navigation = nil
        stopped = error == nil
        if error != nil { webView?.logRuntimeLifecycle("native_stop_failed") }
        let callbacks = waiters
        waiters.removeAll()
        callbacks.forEach { $0(error) }
    }

    func disposed() { finish(nil); stopped = true }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        if navigation === self.navigation {
            self.webView?.logRuntimeLifecycle("native_blank_committed")
            finish(nil)
        }
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if navigation === self.navigation { finish(error) }
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if navigation === self.navigation { finish(error) }
    }
}

/// Retains identity across a native headless -> presentation transfer without
/// extending the WebView lifetime. A missing entry is not release evidence.
final class WeakRuntimeActivityTarget {
    weak var value: InAppWebView?
    var stopConfirmed = false
    init(_ value: InAppWebView?) { self.value = value }
}
