import UIKit
import Lynx

private final class SafeAreaObserverView: UIView {
    var onChange: ((UIEdgeInsets) -> Void)?

    override func safeAreaInsetsDidChange() {
        super.safeAreaInsetsDidChange()
        onChange?(safeAreaInsets)
    }
}

@objcMembers
public final class TamerInsetsModule: NSObject, LynxModule {

    @objc public static var name: String { "TamerInsetsModule" }

    @objc public static var methodLookup: [String: String] {
        [
            "getInsets":  NSStringFromSelector(#selector(getInsets(_:))),
            "getKeyboard": NSStringFromSelector(#selector(getKeyboard(_:))),
        ]
    }

    public static weak var shared: TamerInsetsModule?

    /// Lynx root view (same role as Android `attachHostView`). Required for correct safe-area + keyboard overlap on iOS.
    public static weak var hostView: UIView?
    private static var safeAreaObserver: SafeAreaObserverView?

    @objc public static func attachHostView(_ view: UIView?) {
        DispatchQueue.main.async {
            TamerInsetsModule.safeAreaObserver?.removeFromSuperview()
            TamerInsetsModule.safeAreaObserver = nil
            TamerInsetsModule.hostView = view

            guard let host = view else {
                TamerInsetsModule.shared?.publishCurrentInsets()
                return
            }

            let observer = SafeAreaObserverView()
            observer.frame = host.bounds
            observer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            observer.isUserInteractionEnabled = false
            observer.backgroundColor = .clear
            host.addSubview(observer)
            TamerInsetsModule.safeAreaObserver = observer

            observer.onChange = { _ in
                TamerInsetsModule.shared?.publishCurrentInsets()
            }
            TamerInsetsModule.shared?.publishCurrentInsets()
        }
    }

    @objc public static func reRequestInsets() {
        DispatchQueue.main.async {
            shared?.publishCurrentInsets()
        }
    }

    private weak var lynxContext: LynxContext?

    private var lastTop: CGFloat = -1
    private var lastRight: CGFloat = -1
    private var lastBottom: CGFloat = -1
    private var lastLeft: CGFloat = -1

    private var isKeyboardVisible = false
    private var keyboardHeight: CGFloat = 0
    private var keyboardDuration: Int = 0

    @objc public required init(param: Any) {
        super.init()
        lynxContext = param as? LynxContext
        TamerInsetsModule.shared = self
        DispatchQueue.main.async { [weak self] in self?.setup() }
    }

    @objc public override init() {
        super.init()
        TamerInsetsModule.shared = self
        DispatchQueue.main.async { [weak self] in self?.setup() }
    }

    private func setup() {
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(keyboardWillShow(_:)),
                       name: UIResponder.keyboardWillShowNotification, object: nil)
        nc.addObserver(self, selector: #selector(keyboardWillHide(_:)),
                       name: UIResponder.keyboardWillHideNotification, object: nil)
        nc.addObserver(self, selector: #selector(keyboardWillChange(_:)),
                       name: UIResponder.keyboardWillChangeFrameNotification, object: nil)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            self?.publishCurrentInsets()
        }
    }

    private func keyWindow() -> UIWindow? {
        if #available(iOS 13.0, *) {
            return UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }
        }
        return UIApplication.shared.keyWindow
    }

    private func fallbackSafeAreaInsets() -> UIEdgeInsets {
        if let host = TamerInsetsModule.hostView {
            let direct = host.safeAreaInsets
            if direct.top > 0 || direct.bottom > 0 || direct.left > 0 || direct.right > 0 {
                return direct
            }
            if let superview = host.superview {
                let s = superview.safeAreaInsets
                if s.top > 0 || s.bottom > 0 || s.left > 0 || s.right > 0 { return s }
            }
            return direct
        }
        if let root = keyWindow()?.rootViewController?.view {
            return root.safeAreaInsets
        }
        return keyWindow()?.safeAreaInsets ?? .zero
    }

    fileprivate func publishCurrentInsets() {
        let insets = fallbackSafeAreaInsets()
        handleInsetsChange(insets)
    }

    private func handleInsetsChange(_ insets: UIEdgeInsets) {
        let top = insets.top
        let right = insets.right
        let bottom = insets.bottom
        let left = insets.left

        if top == lastTop && right == lastRight && bottom == lastBottom && left == lastLeft { return }
        lastTop = top
        lastRight = right
        lastBottom = bottom
        lastLeft = left

        let payload = "{\"top\":\(top),\"right\":\(right),\"bottom\":\(bottom),\"left\":\(left)}"
        sendEvent("tamer-insets:change", payload: payload)
    }

    @objc private func keyboardWillShow(_ notification: Notification) {
        handleKeyboardNotification(notification, forceVisible: true)
    }

    @objc private func keyboardWillHide(_ notification: Notification) {
        handleKeyboardNotification(notification, forceVisible: false)
    }

    @objc private func keyboardWillChange(_ notification: Notification) {
        handleKeyboardNotification(notification, forceVisible: nil)
    }

    private func keyboardOverlapHeight(endFrameScreen: CGRect) -> CGFloat {
        if let host = TamerInsetsModule.hostView, let window = host.window {
            let space = window.screen.coordinateSpace
            let kbInHost = host.convert(endFrameScreen, from: space)
            return max(0, host.bounds.intersection(kbInHost).height)
        }

        guard let window = keyWindow() else { return 0 }
        let space = window.screen.coordinateSpace
        let kbInWindow = window.convert(endFrameScreen, from: space)
        return max(0, window.bounds.intersection(kbInWindow).height)
    }

    private func handleKeyboardNotification(_ notification: Notification, forceVisible: Bool?) {
        guard let userInfo = notification.userInfo,
              let endFrameScreen = userInfo[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }

        let overlap = keyboardOverlapHeight(endFrameScreen: endFrameScreen)
        let visible: Bool
        if let forced = forceVisible {
            visible = forced && overlap > 0.5
        } else {
            visible = overlap > 0.5
        }
        let height = visible ? overlap : 0

        let duration = Int(((userInfo[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25) * 1000)

        if visible == isKeyboardVisible && height == keyboardHeight { return }
        isKeyboardVisible = visible
        keyboardHeight = height
        keyboardDuration = duration

        let payload = "{\"visible\":\(visible ? "true" : "false"),\"height\":\(height),\"duration\":\(duration)}"
        sendEvent("tamer-insets:keyboard", payload: payload)
    }

    @objc func getInsets(_ callback: @escaping (Any) -> Void) {
        DispatchQueue.main.async { [weak self] in
            let insets = self?.fallbackSafeAreaInsets() ?? .zero
            callback([
                "top": insets.top,
                "right": insets.right,
                "bottom": insets.bottom,
                "left": insets.left,
            ] as [String: CGFloat])
        }
    }

    @objc func getKeyboard(_ callback: @escaping (Any) -> Void) {
        let visible = isKeyboardVisible && keyboardHeight > 0
        callback([
            "visible": visible,
            "height": visible ? keyboardHeight : 0,
            "duration": keyboardDuration,
        ] as [String: Any])
    }

    private func sendEvent(_ name: String, payload: String) {
        let params: [[String: Any]] = [["payload": payload]]
        DispatchQueue.main.async { [weak self] in
            guard let ctx = self?.lynxContext ?? TamerInsetsModule.shared?.lynxContext else { return }
            ctx.sendGlobalEvent(name, withParams: params)
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        let observer = TamerInsetsModule.safeAreaObserver
        TamerInsetsModule.safeAreaObserver = nil
        if let obs = observer {
            if Thread.isMainThread {
                obs.removeFromSuperview()
            } else {
                DispatchQueue.main.async {
                    obs.removeFromSuperview()
                }
            }
        }
    }
}
