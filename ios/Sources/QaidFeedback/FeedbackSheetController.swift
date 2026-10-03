#if os(iOS)
import SwiftUI
import UIKit
import WebKit

@MainActor
protocol FeedbackSheetDelegate: AnyObject {
    func sheetDidSubmit(kind: FeedbackKind, message: String, screenshot: String?)
    func sheetDidAskToRecord(kind: FeedbackKind?, message: String)
    func sheetDidFinish()
}

/// The full-screen sheet. Normally qaid's hosted annotate page in a web view; if that
/// page can't be reached (offline, blocked, not deployed) it swaps in a native form
/// that sends the same report without the markup tools, so feedback is never lost to
/// a page load.
@MainActor
final class FeedbackSheetController: UIViewController, WKNavigationDelegate {
    private let config: QaidConfiguration
    private var initMessage: InitMessage
    private weak var delegate: FeedbackSheetDelegate?
    private var webView: WKWebView?
    private var spinner = UIActivityIndicatorView(style: .large)
    private var readyTimer: Timer?
    private var pageReady = false
    private var fallback: FallbackModel?

    init(config: QaidConfiguration, initMessage: InitMessage, delegate: FeedbackSheetDelegate) {
        self.config = config
        self.initMessage = initMessage
        self.delegate = delegate
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
        overrideUserInterfaceStyle = initMessage.theme == .dark ? .dark : .light
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    private var background: UIColor {
        initMessage.theme == .dark ? UIColor(white: 0.04, alpha: 1)
            : UIColor(red: 0.953, green: 0.957, blue: 0.965, alpha: 1)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = background

        let configuration = WKWebViewConfiguration()
        // No cookies, no storage: the page needs neither, and nothing should outlive the sheet.
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(WeakScriptHandler(self), name: Bridge.handlerName)
        let web = WKWebView(frame: view.bounds, configuration: configuration)
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        web.isOpaque = false
        web.backgroundColor = background
        web.scrollView.backgroundColor = background
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.navigationDelegate = self
        #if DEBUG
        if #available(iOS 16.4, *) { web.isInspectable = true }
        #endif
        view.addSubview(web)
        webView = web

        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.color = UIColor(hex: initMessage.accent.positive) ?? .systemGreen
        view.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
        spinner.startAnimating()

        var request = URLRequest(url: config.annotateURL, cachePolicy: .reloadRevalidatingCacheData, timeoutInterval: 15)
        request.setValue("1", forHTTPHeaderField: "X-Qaid-Bridge")
        web.load(request)
        // A page that loads but never says "ready" is as good as no page.
        readyTimer = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, !self.pageReady else { return }
                self.showFallback()
            }
        }
    }

    deinit {
        readyTimer?.invalidate()
    }

    /// Relays an upload status to whichever form is showing.
    func show(_ status: StatusMessage) {
        if let fallback {
            fallback.status = status
        } else {
            deliver(BridgeCodec.encode(status))
        }
    }

    private func deliver(_ json: String) {
        webView?.evaluateJavaScript(BridgeCodec.deliveryScript(json), completionHandler: nil)
    }

    // MARK: page → app

    fileprivate func received(_ message: WKScriptMessage) {
        // Only the annotate page itself may drive the sheet — not a frame, not a redirect.
        guard message.frameInfo.isMainFrame, origin(of: message.frameInfo.securityOrigin) == config.annotateOrigin,
              let parsed = BridgeCodec.decodePage(message.body) else { return }
        switch parsed {
        case .ready:
            pageReady = true
            readyTimer?.invalidate()
            spinner.stopAnimating()
            deliver(BridgeCodec.encode(initMessage))
        case let .submit(kind, text, screenshot):
            delegate?.sheetDidSubmit(kind: kind, message: text, screenshot: screenshot)
        case let .record(kind, text):
            delegate?.sheetDidAskToRecord(kind: kind, message: text)
        case .cancel, .close:
            delegate?.sheetDidFinish()
        case .error(let text):
            #if DEBUG
            print("[QaidFeedback] annotate page error: \(text)")
            #endif
        }
    }

    private func origin(of origin: WKSecurityOrigin) -> String {
        let scheme = origin.protocol.lowercased()
        let host = origin.host.lowercased()
        return origin.port == 0 ? "\(scheme)://\(host)" : "\(scheme)://\(host):\(origin.port)"
    }

    // MARK: WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url else { return decisionHandler(.cancel) }
        if url.scheme == "about" || QaidConfiguration.origin(of: url) == config.annotateOrigin {
            decisionHandler(.allow)
        } else {
            // A link off the page never navigates the sheet away.
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 {
            decisionHandler(.cancel)
            showFallback()
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        showFallback()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if !pageReady { showFallback() }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        showFallback()
    }

    // MARK: native fallback

    private func showFallback() {
        guard fallback == nil else { return }
        readyTimer?.invalidate()
        spinner.stopAnimating()
        webView?.navigationDelegate = nil
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: Bridge.handlerName)
        webView?.removeFromSuperview()
        webView = nil

        let model = FallbackModel(initMessage: initMessage)
        fallback = model
        let form = FallbackForm(model: model,
                                onSubmit: { [weak self] kind, text, shot in
                                    self?.delegate?.sheetDidSubmit(kind: kind, message: text, screenshot: shot)
                                },
                                onRecord: { [weak self] kind, text in
                                    self?.delegate?.sheetDidAskToRecord(kind: kind, message: text)
                                },
                                onClose: { [weak self] in self?.delegate?.sheetDidFinish() })
        let host = UIHostingController(rootView: form)
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.view.backgroundColor = background
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }
}

/// WKUserContentController holds its handlers strongly; this keeps it from holding the sheet.
@MainActor
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    weak var target: FeedbackSheetController?

    init(_ target: FeedbackSheetController) {
        self.target = target
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.received(message)
    }
}

extension UIColor {
    convenience init?(hex: String) {
        var text = hex.trimmingCharacters(in: .whitespaces)
        guard text.hasPrefix("#") else { return nil }
        text.removeFirst()
        if text.count == 3 { text = text.map { "\($0)\($0)" }.joined() }
        guard text.count == 6 || text.count == 8, let value = UInt64(text, radix: 16) else { return nil }
        let hasAlpha = text.count == 8
        let r = CGFloat((value >> (hasAlpha ? 24 : 16)) & 0xff) / 255
        let g = CGFloat((value >> (hasAlpha ? 16 : 8)) & 0xff) / 255
        let b = CGFloat((value >> (hasAlpha ? 8 : 0)) & 0xff) / 255
        let a = hasAlpha ? CGFloat(value & 0xff) / 255 : 1
        self.init(red: r, green: g, blue: b, alpha: a)
    }
}
#endif
