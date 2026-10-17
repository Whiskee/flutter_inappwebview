//
//  HeadlessInAppWebViewManager.swift
//  flutter_inappwebview
//
//  Created by Lorenzo Pichilli on 10/05/2020.
//

import Foundation

import Flutter
import UIKit
import WebKit
import Foundation
import AVFoundation

public class HeadlessInAppWebViewManager: ChannelDelegate {
    static let METHOD_CHANNEL_NAME = "com.pichillilorenzo/flutter_headless_inappwebview"
    var plugin: InAppWebViewFlutterPlugin?
    private var runtimeTargets: [String: WeakRuntimeActivityTarget] = [:]
    var webViews: [String: HeadlessInAppWebView?] = [:]
    
    init(plugin: InAppWebViewFlutterPlugin) {
        super.init(channel: FlutterMethodChannel(name: HeadlessInAppWebViewManager.METHOD_CHANNEL_NAME, binaryMessenger: plugin.registrar.messenger()))
        self.plugin = plugin
    }
    
    public override func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        let arguments = call.arguments as? NSDictionary
        let id: String = arguments!["id"] as! String

        switch call.method {
            case "stopRuntimeActivity":
                guard let target = runtimeTargets[id] else { result(false); return }
                guard let webView = target.value else { result(target.stopConfirmed); return }
                webView.stopRuntimeActivity { error in
                    if let error = error { result(FlutterError(code: "runtime_stop_failed", message: error.localizedDescription, details: nil)) }
                    else { result(true) }
                }
                break
            case "run":
                let params = arguments!["params"] as! [String: Any?]
                run(id: id, params: params)
                result(true)
                break
            default:
                result(FlutterMethodNotImplemented)
                break
        }
    }
    
    public func run(id: String, params: [String: Any?]) {
        guard let plugin = plugin else {
            return
        }
        let flutterWebView = FlutterWebViewController(plugin: plugin,
            withFrame: CGRect.zero,
            viewIdentifier: id,
            params: params as NSDictionary)
        if runtimeTargets.count >= 256 {
            for key in runtimeTargets.keys.filter({ runtimeTargets[$0]?.value == nil }) {
                if runtimeTargets.count < 256 { break }
                runtimeTargets.removeValue(forKey: key)
            }
        }
        runtimeTargets[id] = flutterWebView.webView()?.runtimeActivityIdentity()
        let headlessInAppWebView = HeadlessInAppWebView(plugin: plugin, id: id, flutterWebView: flutterWebView)
        webViews[id] = headlessInAppWebView
        
        headlessInAppWebView.prepare(params: params as NSDictionary)
        headlessInAppWebView.onWebViewCreated()
        flutterWebView.makeInitialLoad(params: params as NSDictionary)
    }
    
    public override func dispose() {
        super.dispose()
        let headlessWebViews = webViews.values
        headlessWebViews.forEach { (headlessWebView: HeadlessInAppWebView?) in
            headlessWebView?.dispose()
        }
        webViews.removeAll()
        runtimeTargets.removeAll()
        plugin = nil
    }
    
    deinit {
        dispose()
    }
}
