# Air Mouse (Remote Touchpad)

The phone becomes a trackpad for the desktop: pointer motion, the three mouse
buttons, scrolling, pinch zoom, and the shell gestures a laptop touchpad
offers. Guarded by the `input` capability, so a host that cannot inject
pointer events hides the section rather than offering dead controls.

---

## 🧠 Where the intelligence lives

**Every gesture is recognised on the phone.** The desktop receives resolved
intents — "move 4.2 pixels right", "click the left button", "scroll two
notches up" — and never learns that a finger was involved.

That split is deliberate:

- The feel of the pad (sensitivity, acceleration, tap slop, natural scrolling)
  can be retuned entirely in the Android app, with no protocol change and no
  daemon update.
- The host surface stays four small operations, which is what makes a macOS or
  Linux backend a contained piece of work rather than a port of a gesture
  engine.
- The wire carries no raw touch stream, so a slow link degrades into a coarser
  cursor rather than a mis-recognised gesture.

---

## 🖐️ Gesture vocabulary

| Gesture | Result |
| :--- | :--- |
| One finger drag | Move the pointer |
| One finger tap | Left click |
| Two finger tap | Right click |
| Three finger tap | Middle click |
| Double tap, then drag | Left button held for the drag (tap-and-a-half) |
| Press and hold, then drag | Left button held for the drag |
| Two finger drag | Scroll, vertical and horizontal |
| Two finger pinch | Zoom — sent as a wheel with control held |
| Three finger swipe up | Task View |
| Three finger swipe down | Show desktop |
| Three finger swipe sideways | Switch virtual desktop |

Buttons for left, middle and right also sit below the pad. They are redundant
with tapping on purpose: holding a button while dragging is easier with a
thumb on a real button, and a two-finger right click is awkward one-handed.

Haptics fire on every discrete event — a click, a latched drag, each scroll
notch, a recognised swipe — so the pad confirms what it understood without the
user having to watch the desktop.

### How a stroke is classified

A gesture is tracked from first contact to last release, and its finger count
is a **high-water mark** rather than a live value. Fingers rarely land or lift
in the same frame; a recogniser that reclassified mid-stroke would fire a
stray left click every time a second finger arrived late.

A two-finger stroke commits to **either** scrolling **or** pinching once it
passes a threshold, and stays committed. Without that, a slightly uneven
two-finger scroll also zooms the page.

---

## 🎯 Pointer acceleration

A phone-sized pad cannot cross a 4K desktop at 1:1 without several strokes,
and a pad that simply multiplies everything cannot land on a checkbox. So gain
rises with finger speed and saturates:

```
gain = SENSITIVITY × min(1 + speed × ACCEL_PER_PX, ACCEL_MAX)
```

A slow, careful move stays near 1:1. A flick is amplified up to 3×. The cap
matters — without it a fast stroke throws the cursor off the far edge.

### Sub-pixel motion

`input.move` carries **fractional** pixels, and the host accumulates the
remainder across frames rather than truncating each one.

This is not a nicety. At 120 Hz a careful drag moves well under a pixel per
frame; truncating independently would round every frame to zero and the cursor
would simply refuse to creep. The daemon keeps a running remainder and emits
whole pixels as they complete, so what is asked for is what is delivered.

The phone coalesces motion onto a ~60 Hz tick before sending, so a 120 Hz
panel does not spend half its frames encrypting redundant pointer updates.

---

## 🔌 Wire protocol

Four actions, all fire-and-forget: none broadcasts, and none replies on
success. The pointer moving on screen is the acknowledgement, and a reply per
frame would double the traffic for nothing. Failures still come back, so a
client on an unsupported host finds out immediately.

#### `input.move`

```json
{ "action": "input.move", "payload": { "dx": 4.25, "dy": -1.5 } }
```

Relative motion in host pixels. Fractional; the host carries the remainder.

#### `input.button`

```json
{ "action": "input.button", "payload": { "button": "left", "action": "click" } }
```

*Buttons*: `left`, `right`, `middle`.
*Actions*: `down`, `up`, `click`, `double`.

`down` and `up` exist separately so a drag can hold the button across a whole
run of `input.move` frames. `double` is sent as one action rather than two
clicks so the pair always lands inside the system's double-click interval,
however busy the machine is.

#### `input.scroll`

```json
{ "action": "input.scroll", "payload": { "dx": 0, "dy": -2, "ctrl": true } }
```

Wheel motion in **notches**, one notch per detent of a physical wheel.
Positive `dy` scrolls up, positive `dx` scrolls right. The phone applies the
user's natural-scroll preference before sending, so the host never has to know
about it. `ctrl` asks for the wheel with control held, which is how every
desktop spells "zoom".

#### `input.gesture`

```json
{ "action": "input.gesture", "payload": { "name": "taskView" } }
```

*Names*: `taskView`, `showDesktop`, `desktopLeft`, `desktopRight`, `back`,
`forward`.

**The gesture table is the entire keyboard surface the air mouse exposes.**
The phone never sends key codes, and the host refuses any name outside its
table — so a malformed or hostile frame cannot turn the pointer channel into a
general keyboard.

---

## 🪟 Windows implementation

Injection goes through `user32!SendInput`, the same path the OS uses for a real
device: events enter ahead of the message queue, so they work in any focused
window rather than only in ones that accept synthetic posts.

Each batch is submitted in a single `SendInput` call. That is what guarantees
no other thread's input is interleaved within it, so a modifier and its key
cannot be split apart by a keystroke from the physical keyboard mid-gesture.

| Concern | Mechanism |
| :--- | :--- |
| Motion | `MOUSEEVENTF_MOVE` with relative deltas |
| Buttons | `MOUSEEVENTF_{LEFT,RIGHT,MIDDLE}{DOWN,UP}` |
| Wheel | `MOUSEEVENTF_WHEEL` / `MOUSEEVENTF_HWHEEL`, `mouseData` in units of 120 |
| Gestures | Synthetic key chords, released in reverse so modifiers outlive their key |

### Known limitation: elevated windows

`SendInput` cannot reach a window running at a **higher integrity level** than
the daemon. An elevated application under the cursor will ignore the air mouse
until the daemon itself is elevated.

This is a Windows security boundary — User Interface Privilege Isolation —
and not something to work around. Running the daemon elevated is the only fix,
and it is the user's decision to make.

---

## 🧭 Other platforms

`inputSupported()` reports `false` on macOS and Linux, so the Touchpad section
does not appear. The host surface those backends need is four functions —
`moveMouse`, `mouseButton`, `scrollMouse`, `shellGesture` — because the gesture
engine never left the phone.

---

## ⌨️ Remote Text Input & Universal Clipboard

The Touchpad interface is accompanied by a native typing toolbar and clipboard pusher, transforming the mobile device into a complete mouse and keyboard remote:

### Remote Unicode Text Injection (`input.text`)
- Injects full Unicode character strings directly into whichever application has active focus on the host machine.
- Uses Windows `user32!SendInput` with `KEYEVENTF_UNICODE` (`0x0004`).
- Encodes UTF-8 strings into UTF-16 code units via `unicode/utf16`, supporting multi-byte characters, foreign alphabets, and emojis.
- Works with native mobile keyboards, swipe typing (Gboard), and speech-to-text voice recognition.

### Universal Clipboard Pusher (`clipboard.set`)
- Allows users to copy verification codes, URLs, or text snippets on Android and instantly push them to the Windows clipboard with a single tap.
- Uses Win32 `OpenClipboard`, `EmptyClipboard`, `GlobalAlloc(GMEM_MOVEABLE)`, and `SetClipboardData(CF_UNICODETEXT)`.
- Replaces manual re-typing and cloud messaging relays with instantaneous, end-to-end encrypted local clipboard synchronization.

