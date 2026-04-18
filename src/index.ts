import { useState, useEffect } from 'react'

export interface Insets {
  top: number
  right: number
  bottom: number
  left: number
}

export interface InsetsWithRaw extends Insets {
  raw: Insets
}

export interface KeyboardState {
  visible: boolean
  height: number
  duration: number
}

export interface KeyboardStateWithRaw extends KeyboardState {
  raw: KeyboardState
}

declare const lynx: {
  getJSModule?(id: string): {
    addListener?(e: string, fn: (ev: any) => void): void;
    removeListener?(e: string, fn: unknown): void;
  };
} | undefined

const DEFAULT_RAW_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 }
const DEFAULT_INSETS: InsetsWithRaw = { ...DEFAULT_RAW_INSETS, raw: DEFAULT_RAW_INSETS }
const DEFAULT_RAW_KEYBOARD: KeyboardState = { visible: false, height: 0, duration: 0 }
const DEFAULT_KEYBOARD: KeyboardStateWithRaw = { ...DEFAULT_RAW_KEYBOARD, raw: DEFAULT_RAW_KEYBOARD }

type EventPayload<T> = T | { payload?: string }

function toInsets(raw: Insets): InsetsWithRaw {
  return {
    top: parseFloat(String(raw.top)),
    right: parseFloat(String(raw.right)),
    bottom: parseFloat(String(raw.bottom)),
    left: parseFloat(String(raw.left)),
    raw,
  }
}

function toKeyboard(raw: KeyboardState): KeyboardStateWithRaw {
  const height = parseFloat(String(raw.height))
  const visible = raw.visible && height > 0
  const duration = parseFloat(String(raw.duration ?? 0)) || 0
  return { visible, height: visible ? height : 0, duration, raw }
}

function parsePayload<T>(event: EventPayload<T> | string): T | null {
  if (typeof event === 'string') {
    try { return JSON.parse(event) as T } catch { return null }
  }
  if (event && typeof event === 'object' && 'payload' in event && typeof event.payload === 'string') {
    try {
      return JSON.parse(event.payload) as T
    } catch {
      return null
    }
  }
  return event as T
}

export const TAMER_INSETS_SNAPSHOT_GLOBAL_KEY = '__tamerInsetsSnapshot'

function readInsetsSnapshotFromGlobal(): InsetsWithRaw | null {
  try {
    const g = globalThis as Record<string, unknown>
    const v = g[TAMER_INSETS_SNAPSHOT_GLOBAL_KEY]
    if (
      v != null &&
      typeof v === 'object' &&
      !Array.isArray(v) &&
      typeof (v as InsetsWithRaw).top === 'number' &&
      typeof (v as InsetsWithRaw).raw === 'object'
    ) {
      return v as InsetsWithRaw
    }
  } catch (_) {}
  return null
}

let insetsShared: InsetsWithRaw = readInsetsSnapshotFromGlobal() ?? DEFAULT_INSETS
const insetsSubscribers = new Set<() => void>()

function setInsetsShared(next: InsetsWithRaw) {
  insetsShared = next
  try {
    (globalThis as Record<string, unknown>)[TAMER_INSETS_SNAPSHOT_GLOBAL_KEY] = next
  } catch (_) {}
  insetsSubscribers.forEach((fn) => {
    fn()
  })
}

function subscribeInsets(onChange: () => void) {
  insetsSubscribers.add(onChange)
  return () => {
    insetsSubscribers.delete(onChange)
  }
}

let insetsBridgeAttached = false

function attachInsetsBridgeOnce() {
  if (insetsBridgeAttached) return
  insetsBridgeAttached = true

  const bridge = typeof lynx !== 'undefined' ? lynx?.getJSModule?.('GlobalEventEmitter') : undefined

  const handleInsetsChange = (event: EventPayload<Insets>) => {
    try {
      const nextInsets = parsePayload(event)
      if (nextInsets && typeof nextInsets.top === 'number') setInsetsShared(toInsets(nextInsets))
    } catch (_) {}
  }

  bridge?.addListener?.('tamer-insets:change', handleInsetsChange)

  try {
    NativeModules?.TamerInsetsModule?.getInsets?.((res: any) => {
      const data = parsePayload<Insets>(res)
      if (data && typeof data.top === 'number') setInsetsShared(toInsets(data))
    })
  } catch (_) {}
}

/** Prime the shared insets cache (e.g. from native before first React paint). */
export function seedTamerInsets(raw: Insets): void {
  setInsetsShared(toInsets(raw))
}

export function useInsets() {
  const [insets, setInsets] = useState<InsetsWithRaw>(() => insetsShared)

  useEffect(() => {
    attachInsetsBridgeOnce()
    setInsets(insetsShared)
    return subscribeInsets(() => {
      setInsets(insetsShared)
    })
  }, [])

  return insets
}

function fromLynxKeyboardEvent(evOrIsShow: unknown, heightArg?: unknown): KeyboardState | null {
  let isShow: unknown
  let height: number
  if (heightArg !== undefined && heightArg !== null) {
    isShow = evOrIsShow
    height = parseFloat(String(heightArg)) || 0
  } else if (Array.isArray(evOrIsShow) && evOrIsShow.length >= 2) {
    isShow = evOrIsShow[0]
    height = parseFloat(String(evOrIsShow[1])) || 0
  } else if (evOrIsShow && typeof evOrIsShow === 'object' && 'payload' in evOrIsShow) {
    const p = (evOrIsShow as { payload: unknown }).payload
    if (Array.isArray(p) && p.length >= 2) {
      isShow = p[0]
      height = parseFloat(String(p[1])) || 0
    } else return null
  } else return null
  const visible = isShow === 'on' || isShow === true
  return { visible, height: visible ? height : 0, duration: 250 }
}

let keyboardShared: KeyboardStateWithRaw = DEFAULT_KEYBOARD
const keyboardSubscribers = new Set<() => void>()

function setKeyboardShared(next: KeyboardStateWithRaw) {
  keyboardShared = next
  keyboardSubscribers.forEach((fn) => {
    fn()
  })
}

function subscribeKeyboard(onChange: () => void) {
  keyboardSubscribers.add(onChange)
  return () => {
    keyboardSubscribers.delete(onChange)
  }
}

let keyboardBridgeAttached = false

function hasTamerInsetsKeyboard(): boolean {
  try {
    return typeof NativeModules?.TamerInsetsModule?.getKeyboard === 'function'
  } catch {
    return false
  }
}

function attachKeyboardBridgeOnce() {
  if (keyboardBridgeAttached) return
  keyboardBridgeAttached = true

  const bridge = typeof lynx !== 'undefined' ? lynx?.getJSModule?.('GlobalEventEmitter') : undefined
  const lynxFallback = !hasTamerInsetsKeyboard()

  const handleTamerKeyboard = (event: EventPayload<KeyboardState>) => {
    try {
      const nextKeyboard = parsePayload(event)
      if (nextKeyboard && typeof nextKeyboard.visible === 'boolean') {
        setKeyboardShared(toKeyboard(nextKeyboard))
      }
    } catch (_) {}
  }

  const handleLynxKeyboard = (evOrIsShow: unknown, heightArg?: unknown) => {
    const next = fromLynxKeyboardEvent(evOrIsShow, heightArg)
    if (next) setKeyboardShared(toKeyboard(next))
  }

  bridge?.addListener?.('tamer-insets:keyboard', handleTamerKeyboard)
  if (lynxFallback) {
    bridge?.addListener?.('keyboardstatuschanged', handleLynxKeyboard)
  }

  try {
    NativeModules?.TamerInsetsModule?.getKeyboard?.((res: any) => {
      const data = parsePayload<KeyboardState>(res)
      if (data && typeof data.visible === 'boolean') setKeyboardShared(toKeyboard(data))
    })
  } catch (_) {}
}

export function useKeyboard() {
  const [keyboard, setKeyboard] = useState<KeyboardStateWithRaw>(() => keyboardShared)

  useEffect(() => {
    attachKeyboardBridgeOnce()
    setKeyboard(keyboardShared)
    return subscribeKeyboard(() => {
      setKeyboard(keyboardShared)
    })
  }, [])

  return keyboard
}
