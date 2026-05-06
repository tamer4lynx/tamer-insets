# tamer-insets

Hooks for system insets and keyboard state in Lynx.

**Inset math (Android):** `TamerInsetsModule` matches the **root** window inset extraction in [`react-native-safe-area-context` `SafeAreaUtils`](https://github.com/th3rdwave/react-native-safe-area-context) (API 30+ combined `statusBars | displayCutout | navigationBars | captionBar`; API 23–29 `min(systemBottom, stableBottom)` for the bottom edge; pre-23 visible display frame). *Expo* `expo-status-bar` / `expo-navigation-bar` do **not** provide layout pixel heights; they point at safe-area insets for dimensions.

## Installation

```bash
npm install @tamer4lynx/tamer-insets
```

Add to your app's dependencies and run `t4l link`.

## Usage

```tsx
import { useInsets, useKeyboard, type InsetsWithRaw, type KeyboardStateWithRaw } from '@tamer4lynx/tamer-insets'

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

`height` is the keyboard overlap **above** the bottom layout inset (same bottom as `useInsets()` / `<SafeArea>`: Android same root inset model as RN safe-area-context; iOS `overlap − safeArea.bottom`), so you can combine them in layout without mixing units.

**JS / Lynx:** Use **numeric** lengths for keyboard offset the same way as for `insets` (e.g. `paddingBottom: insets.bottom` in `<SafeArea>`). Avoid mixing bare numbers for insets with `"Npx"` strings for keyboard — `AvoidKeyboard` uses rounded numbers to match.

**Types:**
- `Insets` — `{ top, right, bottom, left }`
- `InsetsWithRaw` — extends Insets with `raw: Insets`
- `KeyboardState` — `{ visible: boolean, height: number, duration: number }`
- `KeyboardStateWithRaw` — extends KeyboardState with `raw`

Listens to `tamer-insets:keyboard` (Android) and `keyboardstatuschanged` (iOS) events.

## Platform

Uses **lynx.ext.json**. Run `t4l link` after adding to your app. Requires `TamerInsetsModule` native module.

**Android:** After the keyboard height updates, the module schedules a few delayed `reRequestInsets()` calls. Some custom ROMs (e.g. LineageOS) report IME size incorrectly on the first frames; re-reading matches what happens when you switch keyboards.

### iOS

After you add your `LynxView` to the hierarchy, call **`TamerInsetsModule.attachHostView(lynxView)`** so safe-area and keyboard overlap match the Lynx surface (home indicator, keyboard). Call **`TamerInsetsModule.attachHostView(nil)`** before tearing the view down. Templates in **tamer-dev-client** / **tamer-host** / **`t4l ios create`** do this automatically; custom `UIViewController` hosts must call it themselves.

**iOS 26.2+ (v0.0.5):** The keyboard visibility threshold was relaxed from `> 0.5pt` to `> 0pt`, aligning with Android. On devices where the keyboard height above the safe area is minimal (common on iOS 26.2), this fixes `useKeyboard()` returning `visible: false` and `AvoidKeyboard` not shifting content.
