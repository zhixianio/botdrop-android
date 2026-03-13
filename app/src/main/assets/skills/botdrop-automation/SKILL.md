# Python Mobile Automation Skill (uiautomator2 HTTPDevice)

## Goal

Use this project's Python `HTTPDevice` client to run reliable step-by-step mobile automation: observe -> act -> observe.

## Prerequisites

1. Check if uiautomator2 is available, install if missing:

```bash
python -c "import uiautomator2" 2>/dev/null || apt install -y python-uiautomator2-botdrop
```

2. Start u2 service and verify endpoint `127.0.0.1:9008`:

```bash
curl -sS http://127.0.0.1:9008/ping
```

- If `ping` responds, prefer `u2.HTTPDevice("http://127.0.0.1:9008")`.

## When to Use

- Real-device UI automation with direct Python control
- One action per step, with LLM deciding the next step from fresh output
- Dynamic app UI flows (especially social apps)

## Baseline Rules

- Default RPC endpoint: `http://127.0.0.1:9008` (normalized to `/jsonrpc/0`)
- Log after every step:
  - `app_current()`
  - `d(...).exists` / `d(...).count` / `d(...).center()`
  - `d.xpath(...).exists` / `d.xpath(...).all()` (use `len()` to count)
  - action return values (`click` / `set_text`)
- Prefer short single-step scripts over long one-shot flows

## Python Import and Initialization

```python
import uiautomator2 as u2
```

Use `HTTPDevice` directly (recommended for remote control):

```python
d = u2.HTTPDevice("http://127.0.0.1:9008")
```

Or using the explicit connector:

```python
d = u2.connect_http("http://127.0.0.1:9008")
```

## Initialize Device

```python
import uiautomator2 as u2

d = u2.HTTPDevice("http://127.0.0.1:9008")
print(d.app_current())
print(d.info["currentPackageName"])
```

## Recommended API Priority

1. `d.xpath(...)` (preferred)
- Best for dynamic pages, multilingual text, and complex structures
- Example:
```python
if d.xpath('//*[@text="Settings"]').exists:
    d.xpath('//*[@text="Settings"]').click()
```

2. `d(...)` selector facade
- `exists` (or `exists(timeout=...)`), `wait(timeout=...)`, `wait_gone(timeout=...)`
- `click/click_exists`
- `set_text/get_text/clear_text`
- `count` and instance by index, e.g. `d(text="Settings")[0]`
- `right/left/up/down`
- `center()/info/screenshot()`

## Generic Interaction Patterns

### Two-step entry (menu-expand pattern)

- First tap often expands a menu while `activity` stays unchanged
- Second tap enters the target page

Rules:
1. Check `app_current()` after each tap
2. If `activity` is unchanged, inspect XML for new overlay/menu nodes
3. Click only nodes with `@clickable="true"`
4. Avoid same-label nodes that are `clickable=false`

### Two-step exit (navigate-up + confirm dialog)

Rules:
1. Tap navigate-up first (for example `Navigate up`)
2. Handle confirm dialog, prefer non-keep action (`Delete/Discard`)
3. Use `press('back')` only as fallback
4. Re-check `app_current()` after each step until back at home page

## Chinese Input Strategy

1. Prefer uiautomator2 FastInputIME for reliable CJK input:
   - `d.set_fastinput_ime(True)` to switch to FastInputIME
   - `d.send_keys("中文文本")` to input via ADB broadcast
   - `d.set_fastinput_ime(False)` to restore original IME
2. Fall back to `set_text` only if FastInputIME is unavailable
3. Verify send/post button visibility after input

### FastInputIME broadcast contract (important)

When `send_keys` is used, `uiautomator2` sends an ADB broadcast using these exact contracts:

| Operation | Action | Param |
| - | - | - |
| input text | `ADB_KEYBOARD_INPUT_TEXT` | `--es text` |
| set/replace text (clear + input) | `ADB_KEYBOARD_SET_TEXT` | `--es text` |
| clear text | `ADB_KEYBOARD_CLEAR_TEXT` | no param |
| key event | `ADB_KEYBOARD_INPUT_KEYCODE` | `--ei code` |

- Do not use `--es msg` for text; only `text` is accepted.
- `text` payload must be Base64 encoded.
- Example:
  - `TEXT=$(printf '%s' "周末的雨" | base64)`
  - `am broadcast -a ADB_KEYBOARD_SET_TEXT --es text "$TEXT"`

If broadcast does not take effect, verify action name and `text` parameter first. `ADB_INPUT_TEXT`/`--es msg` is invalid here and can silently fail.

## Debug Checklist (tap has no effect)

1. Verify `app_current()` change
2. Verify node `@clickable`
3. Inspect node `raw` (`content-desc/text/bounds`)
4. Confirm whether this is a two-step entry pattern
5. On exit failures, capture actual dialog button texts first

## Input Reliability Model (State & Focus First)

When text input fails, treat it as a state/focus problem first, not an IME problem.

### 1) Input Gates (must pass all)

- Page state gate: you are in the correct interaction layer (not parent/sibling page)
- Target gate: tap the real editable text area, not a visual container
- Focus gate: input control is active (focused/cursor/placeholder change)

If any gate fails, do not send text yet.

### 2) Execution Order: establish -> focus -> input -> verify

1. Establish correct UI state
2. Focus input area (separate action)
3. Wait 300-800ms
4. Send text once
5. Verify text appears

If verification fails, go back to focus step. Do not spam input calls.

### 3) High-risk operations

- Avoid clear/reset before focus is confirmed.
- Clear operations may destroy contextual state (thread/reply/draft).

### 4) Rendering limitations fallback

When hierarchy is incomplete (custom rendering):
- Use stable visual anchors
- Use anchor-relative taps instead of blind global coordinates

### 5) Gesture safety

- Prefer explicit controls over gestures when entering input flows.
- Gestures can trigger navigation/feed switches unexpectedly.

### 6) Recovery strategy

On repeated input failure:
1. Return to a known stable page/state
2. Re-enter target interaction layer
3. Re-focus input
4. Input and verify again

Core rule: verify state + focus before changing keyboard strategy.
