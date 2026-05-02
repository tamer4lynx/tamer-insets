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
    private static let instancesLock = NSLock()
    private static let instances = NSHashTable<TamerInsetsModule>.weakObjects()

    /// Lynx root view (same role as Android `attachHostView`). Required for correct safe-area + keyboard overlap on iOS.
    public static weak var hostView: UIView?
    private static var safeAreaObserver: SafeAreaObserverView?

    private static func registerInstance(_ instance: TamerInsetsModule) {
        instancesLock.lock()
        instances.add(instance)
        instancesLock.unlock()
    }

    private static func liveInstances() -> [TamerInsetsModule] {
        instancesLock.lock()
        let all = instances.allObjects
        instancesLock.unlock()
        return all
    }

    @objc public static func attachHostView(_ view: UIView?) {
        DispatchQueue.main.async {
            TamerInsetsModule.safeAreaObserver?.removeFromSuperview()
            TamerInsetsModule.safeAreaObserver = nil
            TamerInsetsModule.hostView = view

            // Reset per-instance caches so next publish always emits to each active Lynx runtime.
            TamerInsetsModule.liveInstances().forEach { $0.resetInsetsCache() }

            guard let host = view else {
                TamerInsetsModule.liveInstances().forEach { $0.publishCurrentInsets() }
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
                TamerInsetsModule.liveInstances().forEach { $0.publishCurrentInsets() }
            }
            TamerInsetsModule.liveInstances().forEach { $0.publishCurrentInsets() }
        }
    }

    fileprivate func resetInsetsCache() {
        deferredInsetDecreaseWorkItem?.cancel()
        deferredInsetDecreaseWorkItem = nil
        lastTop = -1
        lastRight = -1
        lastBottom = -1
        lastLeft = -1
    }

    @objc public static func reRequestInsets() {
        DispatchQueue.main.async {
            liveInstances().forEach { $0.publishCurrentInsets() }
        }
    }

    private static func staticKeyWindow() -> UIWindow? {
        if #available(iOS 13.0, *) {
            return UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }
        }
        return UIApplication.shared.keyWindow
    }

    /// Live read of the merged safe-area insets (host view + key window per edge max).
    /// Returns `nil` only when neither window nor host view is available yet.
    @objc public static func currentInsets() -> NSValue? {
        let windowInsets = staticKeyWindow()?.safeAreaInsets ?? .zero
        if let host = TamerInsetsModule.hostView {
            let h = host.safeAreaInsets
            let merged = UIEdgeInsets(
                top: max(h.top, windowInsets.top),
                left: max(h.left, windowInsets.left),
                bottom: max(h.bottom, windowInsets.bottom),
                right: max(h.right, windowInsets.right)
            )
            return NSValue(uiEdgeInsets: merged)
        }
        if staticKeyWindow() == nil {
            return nil
        }
        return NSValue(uiEdgeInsets: windowInsets)
    }

    /// JSON dictionary for embedding in Lynx `initialData` so the JS bundle reads
    /// real insets on its very first render rather than starting at zero and
    /// snapping when `tamer-insets:change` arrives 50–150 ms later.
    @objc public static func currentInsetsSnapshotJson() -> String? {
        guard let value = currentInsets() else { return nil }
        let i = value.uiEdgeInsetsValue
        return "{\"top\":\(i.top),\"right\":\(i.right),\"bottom\":\(i.bottom),\"left\":\(i.left)}"
    }

    private weak var lynxContext: LynxContext?

    private var lastTop: CGFloat = -1
    private var lastRight: CGFloat = -1
    private var lastBottom: CGFloat = -1
    private var lastLeft: CGFloat = -1

    private var isKeyboardVisible = false
    private var keyboardHeight: CGFloat = 0
    private var keyboardDuration: Int = 0
    private var pendingHideWorkItem: DispatchWorkItem?
    private let hideDebounceInterval: TimeInterval = 0.08

    /// Suppresses transient vertical inset *drops* during stack transitions (host/window mismatch):
    /// defer commit ~one frame so a bounce-back does not emit an intermediate AppBar height.
    private var deferredInsetDecreaseWorkItem: DispatchWorkItem?
    private let insetDecreaseDeferSeconds: TimeInterval = 0.048
    private let insetDecreaseThresholdPt: CGFloat = 1.0

    @objc public required init(param: Any) {
        super.init()
        lynxContext = param as? LynxContext
        TamerInsetsModule.shared = self
        TamerInsetsModule.registerInstance(self)
        DispatchQueue.main.async { [weak self] in self?.setup() }
    }

    @objc public override init() {
        super.init()
        TamerInsetsModule.shared = self
        TamerInsetsModule.registerInstance(self)
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
        let windowInsets = keyWindow()?.safeAreaInsets ?? .zero
        guard let host = TamerInsetsModule.hostView else {
            return windowInsets
        }
        let h = host.safeAreaInsets
        // During stack transitions the coordinator host can briefly report zero insets while the
        // window still has the real safe area — merging avoids AppBar height/padding snapping.
        return UIEdgeInsets(
            top: max(h.top, windowInsets.top),
            left: max(h.left, windowInsets.left),
            bottom: max(h.bottom, windowInsets.bottom),
            right: max(h.right, windowInsets.right)
        )
    }

    fileprivate func publishCurrentInsets() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.handleInsetsChange(self.fallbackSafeAreaInsets(), allowDefer: true)
        }
    }

    private func handleInsetsChange(_ insets: UIEdgeInsets, allowDefer: Bool = true) {
        if allowDefer {
            let top = insets.top
            let bottom = insets.bottom
            let vertDecrease =
                (lastTop >= 0 && top < lastTop - insetDecreaseThresholdPt)
                || (lastBottom >= 0 && bottom < lastBottom - insetDecreaseThresholdPt)
            if vertDecrease {
                deferredInsetDecreaseWorkItem?.cancel()
                let work = DispatchWorkItem { [weak self] in
                    guard let self = self else { return }
                    self.deferredInsetDecreaseWorkItem = nil
                    self.handleInsetsChange(self.fallbackSafeAreaInsets(), allowDefer: false)
                }
                deferredInsetDecreaseWorkItem = work
                DispatchQueue.main.asyncAfter(deadline: .now() + insetDecreaseDeferSeconds, execute: work)
                return
            }
        }
        deferredInsetDecreaseWorkItem?.cancel()
        deferredInsetDecreaseWorkItem = nil
        commitInsetsIfChanged(insets)
    }

    private func commitInsetsIfChanged(_ insets: UIEdgeInsets) {
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
        let safeBottom = fallbackSafeAreaInsets().bottom
        NSLog("[TamerInsets] kb notif=%@ endScreen=%@ overlap=%0.2f safeBottom=%0.2f host=%@ hostWindow=%@",
              notification.name.rawValue,
              NSCoder.string(for: endFrameScreen),
              overlap,
              safeBottom,
              TamerInsetsModule.hostView.map { "\($0)" } ?? "nil",
              (TamerInsetsModule.hostView?.window).map { "\($0)" } ?? "nil")
        let overlapVisible: Bool
        if let forced = forceVisible {
            overlapVisible = forced && overlap > 0.5
        } else {
            overlapVisible = overlap > 0.5
        }
        // Overlap includes the home-indicator strip; subtract so JS matches SafeArea bottom padding.
        let heightAboveSafe = overlapVisible ? max(overlap - safeBottom, 0) : 0
        let visible = overlapVisible && heightAboveSafe > 0
        let height = visible ? heightAboveSafe : 0
        let duration = Int(((userInfo[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double) ?? 0.25) * 1000)

        if visible {
            // Cancel any pending hide — this is a show or re-show.
            pendingHideWorkItem?.cancel()
            pendingHideWorkItem = nil
            if visible == isKeyboardVisible && height == keyboardHeight { return }
            isKeyboardVisible = visible
            keyboardHeight = height
            keyboardDuration = duration
            let payload = "{\"visible\":true,\"height\":\(height),\"duration\":\(duration)}"
            sendEvent("tamer-insets:keyboard", payload: payload)
        } else {
            // Debounce the hide: iOS fires keyboardWillHide → keyboardWillShow when
            // focus moves between inputs. If a show arrives within hideDebounceInterval
            // the hide is cancelled and JS never sees the collapsed state.
            if !isKeyboardVisible && keyboardHeight == 0 { return }
            pendingHideWorkItem?.cancel()
            let work = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.pendingHideWorkItem = nil
                if self.isKeyboardVisible || self.keyboardHeight > 0 {
                    self.isKeyboardVisible = false
                    self.keyboardHeight = 0
                    self.keyboardDuration = duration
                    self.sendEvent("tamer-insets:keyboard",
                                   payload: "{\"visible\":false,\"height\":0,\"duration\":\(duration)}")
                }
            }
            pendingHideWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + hideDebounceInterval, execute: work)
        }
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
            let ctx = self?.lynxContext ?? TamerInsetsModule.shared?.lynxContext
            ctx?.sendGlobalEvent(name, withParams: params)
        }
    }

    deinit {
        pendingHideWorkItem?.cancel()
        pendingHideWorkItem = nil
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
