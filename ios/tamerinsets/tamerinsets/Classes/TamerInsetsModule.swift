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

    @objc public static func reRequestInsets() {
        DispatchQueue.main.async {
            shared?.publishCurrentInsets()
        }
    }

    private weak var lynxContext: LynxContext?
    private var observerView: SafeAreaObserverView?

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
        attachObserverView()
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(keyboardWillShow(_:)),
                       name: UIResponder.keyboardWillShowNotification, object: nil)
        nc.addObserver(self, selector: #selector(keyboardWillHide(_:)),
                       name: UIResponder.keyboardWillHideNotification, object: nil)
        nc.addObserver(self, selector: #selector(keyboardWillChange(_:)),
                       name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
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

    private func attachObserverView() {
        guard let window = keyWindow() else {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.attachObserverView()
            }
            return
        }

        let observer = SafeAreaObserverView()
        observer.frame = window.bounds
        observer.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        observer.isUserInteractionEnabled = false
        observer.backgroundColor = .clear
        window.addSubview(observer)
        observerView = observer

        observer.onChange = { [weak self] insets in
            self?.handleInsetsChange(insets)
        }

        handleInsetsChange(window.safeAreaInsets)
    }

    private func publishCurrentInsets() {
        let window = keyWindow()
        let insets = observerView?.safeAreaInsets ?? window?.safeAreaInsets ?? .zero
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

    private func handleKeyboardNotification(_ notification: Notification, forceVisible: Bool?) {
        guard let userInfo = notification.userInfo,
              let endFrame = userInfo[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }

        let windowHeight: CGFloat
        if #available(iOS 13.0, *) {
            windowHeight = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }?.bounds.height ?? UIScreen.main.bounds.height
        } else {
            windowHeight = UIScreen.main.bounds.height
        }

        let rawHeight = max(0, windowHeight - endFrame.origin.y)
        let visible = forceVisible ?? (rawHeight > 1)
        let height = visible ? rawHeight : 0
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
            let window = self?.keyWindow()
            let insets = self?.observerView?.safeAreaInsets ?? window?.safeAreaInsets ?? .zero
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
        observerView?.removeFromSuperview()
    }
}
