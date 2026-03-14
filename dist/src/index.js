import { useState, useEffect } from 'react';
const DEFAULT_RAW_INSETS = { top: 0, right: 0, bottom: 0, left: 0 };
const DEFAULT_INSETS = { ...DEFAULT_RAW_INSETS, raw: DEFAULT_RAW_INSETS };
const DEFAULT_RAW_KEYBOARD = { visible: false, height: 0 };
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
    return { visible, height: visible ? height : 0, raw };
}
function parsePayload(event) {
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
export function useInsets() {
    const [insets, setInsets] = useState(DEFAULT_INSETS);
    useEffect(() => {
        const bridge = typeof lynx !== 'undefined' ? lynx?.getJSModule?.('GlobalEventEmitter') : undefined;
        const handleInsetsChange = (event) => {
            try {
                const nextInsets = parsePayload(event);
                if (nextInsets && typeof nextInsets.top === 'number')
                    setInsets(toInsets(nextInsets));
            }
            catch (_) { }
        };
        bridge?.addListener?.('tamer-insets:change', handleInsetsChange);
        try {
            NativeModules?.TamerInsetsModule?.getInsets?.((res) => {
                if (res && typeof res.top === 'number')
                    setInsets(toInsets(res));
            });
        }
        catch (_) { }
        return () => {
            bridge?.removeListener?.('tamer-insets:change', handleInsetsChange);
        };
    }, []);
    return insets;
}
export function useKeyboard() {
    const [keyboard, setKeyboard] = useState(DEFAULT_KEYBOARD);
    useEffect(() => {
        const bridge = typeof lynx !== 'undefined' ? lynx?.getJSModule?.('GlobalEventEmitter') : undefined;
        const handleKeyboardChange = (event) => {
            try {
                const nextKeyboard = parsePayload(event);
                if (nextKeyboard && typeof nextKeyboard.visible === 'boolean')
                    setKeyboard(toKeyboard(nextKeyboard));
            }
            catch (_) { }
        };
        bridge?.addListener?.('tamer-insets:keyboard', handleKeyboardChange);
        try {
            NativeModules?.TamerInsetsModule?.getKeyboard?.((res) => {
                if (res && typeof res.visible === 'boolean')
                    setKeyboard(toKeyboard(res));
            });
        }
        catch (_) { }
        return () => {
            bridge?.removeListener?.('tamer-insets:keyboard', handleKeyboardChange);
        };
    }, []);
    return keyboard;
}
