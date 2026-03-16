# tamer-insets

Hooks for system insets and keyboard state in Lynx.

## Installation

```bash
npm install tamer-insets
```

Add to your app's dependencies and run `t4l link`.

## Usage

```tsx
import { useInsets, useKeyboard, type InsetsWithRaw, type KeyboardStateWithRaw } from 'tamer-insets'

function MyComponent() {
  const insets = useInsets()
  const keyboard = useKeyboard()

  return (
    <view style={{ paddingTop: insets.top, paddingBottom: insets.bottom }}>
      <text>Keyboard visible: {keyboard.visible ? 'yes' : 'no'}, height: {keyboard.height}</text>
    </view>
  )
}
```

## API

| Hook | Returns | Description |
|------|---------|-------------|
| `useInsets()` | `InsetsWithRaw` | `{ top, right, bottom, left, raw }` — system safe area insets |
| `useKeyboard()` | `KeyboardStateWithRaw` | `{ visible, height, duration, raw }` — keyboard visibility, height (px), and animation duration (ms) |

**Types:**
- `Insets` — `{ top, right, bottom, left }`
- `InsetsWithRaw` — extends Insets with `raw: Insets`
- `KeyboardState` — `{ visible: boolean, height: number, duration: number }`
- `KeyboardStateWithRaw` — extends KeyboardState with `raw`

Listens to `tamer-insets:keyboard` (Android) and `keyboardstatuschanged` (iOS) events.

## Platform

Uses **lynx.ext.json**. Run `t4l link` after adding to your app. Requires `TamerInsetsModule` native module.
