import { useState, useEffect } from 'react';
const DEFAULT_RAW_INSETS = { top: 0, right: 0, bottom: 0, left: 0 };
const DEFAULT_INSETS = { ...DEFAULT_RAW_INSETS, raw: DEFAULT_RAW_INSETS };
const DEFAULT_RAW_KEYBOARD = { visible: false, height: 0, duration: 0 };
const DEFAULT_KEYBOARD = { ...DEFAULT_RAW_KEYBOARD, raw: DEFAULT_RAW_KEYBOARD };
function toInsets(raw) {
    return {
        top: parseFloat(String(raw.top)),
        right: parseFloat(String(raw.right)),
        bottom: parseFloat(String(raw.bottom)),
        left: parseFloat(String(raw.left)),
        raw,
    };
}
function toKeyboard(raw) {
    const height = parseFloat(String(raw.height));
    const visible = raw.visible && height > 0;
    const duration = parseFloat(String(raw.duration ?? 0)) || 0;
    return { visible, height: visible ? height : 0, duration, raw };
}
function parsePayload(event) {
    if (typeof event === 'string') {
        try {
            return JSON.parse(event);
        }
        catch {
            return null;
        }
    }
    if (event && typeof event === 'object' && 'payload' in event && typeof event.payload === 'string') {
        try {
            return JSON.parse(event.payload);
        }
        catch {
            return null;
        }
    }
    return event;
}
export const TAMER_INSETS_SNAPSHOT_GLOBAL_KEY = '__tamerInsetsSnapshot';
function readInsetsSnapshotFromGlobal() {
    try {
        const g = globalThis;
        const v = g[TAMER_INSETS_SNAPSHOT_GLOBAL_KEY];
        if (v != null &&
            typeof v === 'object' &&
            !Array.isArray(v) &&
            typeof v.top === 'number' &&
            typeof v.raw === 'object') {
            return v;
        }
    }
    catch (_) { }
    return null;
}
let insetsShared = readInsetsSnapshotFromGlobal() ?? DEFAULT_INSETS;
const insetsSubscribers = new Set();
function setInsetsShared(next) {
    insetsShared = next;
    try {
        globalThis[TAMER_INSETS_SNAPSHOT_GLOBAL_KEY] = next;
    }
    catch (_) { }
    insetsSubscribers.forEach((fn) => {
        fn();
    });
}
function subscribeInsets(onChange) {
    insetsSubscribers.add(onChange);
    return () => {
        insetsSubscribers.delete(onChange);
    };
}
let insetsBridgeAttached = false;
function attachInsetsBridgeOnce() {
    if (insetsBridgeAttached)
        return;
    insetsBridgeAttached = true;
    const bridge = typeof lynx !== 'undefined' ? lynx?.getJSModule?.('GlobalEventEmitter') : undefined;
    const handleInsetsChange = (event) => {
        try {
            const nextInsets = parsePayload(event);
            if (nextInsets && typeof nextInsets.top === 'number')
                setInsetsShared(toInsets(nextInsets));
        }
        catch (_) { }
    };
    bridge?.addListener?.('tamer-insets:change', handleInsetsChange);
    try {
        NativeModules?.TamerInsetsModule?.getInsets?.((res) => {
            const data = parsePayload(res);
            if (!data || typeof data.top !== 'number')
                return;
            // Ignore all-zero responses that happen when native reads insets before the
            // host view has been laid out; the subsequent `tamer-insets:change` bridge
            // event will deliver real values.
            if (!data.top && !data.bottom && !data.left && !data.right)
                return;
            setInsetsShared(toInsets(data));
        });
    }
    catch (_) { }
}
/** Prime the shared insets cache (e.g. from native before first React paint). */
export function seedTamerInsets(raw) {
    setInsetsShared(toInsets(raw));
}
export function useInsets() {
    const [insets, setInsets] = useState(() => insetsShared);
    useEffect(() => {
        attachInsetsBridgeOnce();
        setInsets(insetsShared);
        return subscribeInsets(() => {
            setInsets(insetsShared);
        });
    }, []);
    return insets;
}
function fromLynxKeyboardEvent(evOrIsShow, heightArg) {
    let isShow;
    let height;
    if (heightArg !== undefined && heightArg !== null) {
        isShow = evOrIsShow;
        height = parseFloat(String(heightArg)) || 0;
    }
    else if (Array.isArray(evOrIsShow) && evOrIsShow.length >= 2) {
        isShow = evOrIsShow[0];
        height = parseFloat(String(evOrIsShow[1])) || 0;
    }
    else if (evOrIsShow && typeof evOrIsShow === 'object' && 'payload' in evOrIsShow) {
        const p = evOrIsShow.payload;
        if (Array.isArray(p) && p.length >= 2) {
            isShow = p[0];
            height = parseFloat(String(p[1])) || 0;
        }
        else
            return null;
    }
    else
        return null;
    const visible = isShow === 'on' || isShow === true;
    return { visible, height: visible ? height : 0, duration: 250 };
}
let keyboardShared = DEFAULT_KEYBOARD;
const keyboardSubscribers = new Set();
function setKeyboardShared(next) {
    keyboardShared = next;
    keyboardSubscribers.forEach((fn) => {
        fn();
    });
}
function subscribeKeyboard(onChange) {
    keyboardSubscribers.add(onChange);
    return () => {
        keyboardSubscribers.delete(onChange);
    };
}
let keyboardBridgeAttached = false;
function hasTamerInsetsKeyboard() {
    try {
        return typeof NativeModules?.TamerInsetsModule?.getKeyboard === 'function';
    }
    catch {
        return false;
    }
}
function attachKeyboardBridgeOnce() {
    if (keyboardBridgeAttached)
        return;
    keyboardBridgeAttached = true;
    const bridge = typeof lynx !== 'undefined' ? lynx?.getJSModule?.('GlobalEventEmitter') : undefined;
    const lynxFallback = !hasTamerInsetsKeyboard();
    const handleTamerKeyboard = (event) => {
        try {
            const nextKeyboard = parsePayload(event);
            if (nextKeyboard && typeof nextKeyboard.visible === 'boolean') {
                setKeyboardShared(toKeyboard(nextKeyboard));
            }
        }
        catch (_) { }
    };
    const handleLynxKeyboard = (evOrIsShow, heightArg) => {
        const next = fromLynxKeyboardEvent(evOrIsShow, heightArg);
        if (next)
            setKeyboardShared(toKeyboard(next));
    };
    bridge?.addListener?.('tamer-insets:keyboard', handleTamerKeyboard);
    if (lynxFallback) {
        bridge?.addListener?.('keyboardstatuschanged', handleLynxKeyboard);
    }
    try {
        NativeModules?.TamerInsetsModule?.getKeyboard?.((res) => {
            const data = parsePayload(res);
            if (!data || typeof data.visible !== 'boolean')
                return;
            // Don't overwrite with default `{visible:false,height:0}`: initial state is
            // already that, and letting it through discards any bridge event that raced
            // before this callback settled.
            if (!data.visible && !data.height)
                return;
            setKeyboardShared(toKeyboard(data));
        });
    }
    catch (_) { }
}
export function useKeyboard() {
    const [keyboard, setKeyboard] = useState(() => keyboardShared);
    useEffect(() => {
        attachKeyboardBridgeOnce();
        setKeyboard(keyboardShared);
        return subscribeKeyboard(() => {
            setKeyboard(keyboardShared);
        });
    }, []);
    return keyboard;
}
