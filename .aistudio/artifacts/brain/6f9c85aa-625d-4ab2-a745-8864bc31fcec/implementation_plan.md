# Pro Workstation Ortholinear Keyboard & Toggleable Split-View

Upgrades the low-latency USB Trackpad into a complete, zero-overhead Linux workstation input surface featuring a 3-mode view switcher (**Trackpad-Only**, **Split-View**, and **Full-Keyboard**) and a custom bare-metal **14×6 Ortholinear Pro Keyboard** streaming native Linux `evdev` keycodes over the existing 8-byte binary ADB tunnel.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> All Phase 1 preferences have been locked into this design, paired with a protective two-tap safety guard on **Sleep** and **Power/Shutdown** so stray touches while typing cannot accidentally suspend or power off your closed-lid laptop.

- **Confirmed Decision (Keyboard Geometry)**: **14×6 Symmetrical Ortholinear Matrix** (inspired by Planck/Preonic pro boards). Strict vertical column alignment eliminates staggered-row finger drift on flat glass while keeping full US ANSI punctuation (`-`, `=`, `[`, `]`, `\`, `;`, `'`, `,`, `.`, `/`, `` ` ``) on the base layer.
- **Confirmed Decision (Dedicated System & Function Keys)**: Top strip includes 1-tap **`ESC`**, **`F4`** (pairs with latched/held `Alt` for `Alt+F4`, or `Fn + F4` for instant close-window chord), **`Mute`**, **`Vol-`**, **`Vol+`**, **`Play/Pause`**, **`Sleep`**, and **`Power/Shutdown`**, with full `F1–F12` and `Home/End/PgUp/PgDn` available via `Fn`.
- **Confirmed Decision (3-Way Mode Cycling)**: Top toolbar pill cycles cleanly through **Trackpad-Only** → **Split-View** (top trackpad + bottom ortholinear keyboard) → **Full-Keyboard** (full-screen spacious keycaps), while preserving **OLED Stealth Mode** for pure-black zero-GPU operation.
- **Confirmed Decision (Modifier Behavior)**: **Hybrid Hold-to-Chord + Single-Tap Sticky Latch** for `Ctrl`, `Alt`, `Shift`, `Super`, and `Fn` (hold with one finger while typing with another, tap once to latch for the next key, tap twice to lock, tap again to clear).
- **Confirmed Decision (Workstation Extras)**: Built-in **Key-Hold Auto-Repeat** (`Backspace`, `Delete`, `Arrows`, `Space`, `Volume`), dedicated **`Lang` (`Super + Space`)** OS language switcher key, and comprehensive **README documentation** explaining architecture, code internals, protocol bytes, gestures, keyboard layers, and Ubuntu service management.

---

## 1. Overview & Core Concept

- **What It Does**: Combines sub-millisecond relative pointer control with a hardware-grade ortholinear keyboard directly on the Android tablet's native `Canvas`—completely bypassing Android's laggy soft IME (`InputMethodManager`) and injecting real hardware scancodes into the Linux kernel via `/dev/uinput`.
- **Target Audience / Persona**: Linux developers and power users operating a closed-lid docked laptop (such as an Acer Spin 5 connected to an external monitor) who want instant, tactile cursor, shortcut, media, power, and typing control over a single offline USB cable.
- **Key Value**: Zero autocorrect buffering, zero window-resize stutter on 1 GB RAM hardware, 100% compatibility with X11/Wayland/TTY shortcuts (`Ctrl+C`, `Alt+F4`, `Super+Space`, `Alt+Tab`), and seamless switching between precision mousing and two-handed touch typing.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Mode Cycling**: Tapping the **`View: Trackpad / Split / Keyboard`** pill in the header transitions smoothly between:
   - *Trackpad-Only*: Full touch surface + optional bottom Left/Mid/Right click bar (and OLED Stealth toggle).
   - *Split-View*: Top ~44% interactive trackpad surface (with compact tactile click strip) + Bottom ~56% 14×6 Ortholinear Keyboard.
   - *Full-Keyboard*: Full-height 14×6 Ortholinear Keyboard with generous vertical keycap targets for fast two-handed typing.
2. **Instant Key Actuation & Repeat**: Keys actuate immediately on `ACTION_DOWN` (not finger lift) for crisp mechanical responsiveness, with subtle haptic tick feedback (when supported by hardware) and high-contrast cyan keycap illumination. Holding `Backspace`, `Delete`, `Arrows`, `Space`, or `Vol-/+` triggers smooth auto-repeat after a 380ms threshold at ~28Hz.
3. **Chording & Sticky Modifiers**:
   - *Physical Chord*: Hold `Alt` with left thumb + tap `F4` → closes active Linux window; release `Alt`.
   - *One-Shot Sticky Latch*: Tap `Ctrl` once (keycap border glows amber `LATCHED`), then tap `C` → sends `Ctrl+C` and automatically unlatches `Ctrl`.
   - *Guarded Power Keys*: Tapping `Sleep` or `Power` once highlights the key in warning red (`TAP AGAIN`); tapping a second time within 2 seconds dispatches `KEY_SLEEP` or `KEY_POWER` to Ubuntu, preventing accidental shutdowns.

### Visual Identity & Theme
- **Aesthetic Direction**: Industrial Precision Workstation Dark (inspired by anodized aluminum ortholinear mechanical keyboards with subtle legend hierarchy).
- **Color Palette & Tokens**:
  - Surface Canvas (`--background`): Deep Obsidian `#0A0A0C` (and `#000000` in OLED Stealth Mode)
  - Alpha Keycaps (`--surface`): Matte Slate `#161922` with `#262B38` hairline borders
  - Modifier / System Keycaps (`--surface-variant`): Charcoal `#11131A` with muted cool-gray legends `#9AA2B8`
  - Active / Pressed Accent (`--primary`): Electric Cyan `#00E5FF`
  - Sticky Latch Accent (`--warning`): Warm Amber `#FFB300`
  - Power / Sleep Guard Accent (`--destructive`): Coral Red `#FF5252`
- **Typography & Keycap Hierarchy**: Bold centered primary legends, dim top-right secondary shifted/Fn legends (`! @ #`, `F1–F12`, `Home/End`), and subtle tactile homing bars rendered beneath **`F`** and **`J`**.

### 14×6 Symmetrical Ortholinear Matrix

```
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ ESC │ F1  │ F2  │ F4  │ F5  │ F11 │ F12 │PrtSc│Mute │Vol- │Vol+ │Play │Sleep│Power│
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│ ` ~ │ 1 ! │ 2 @ │ 3 # │ 4 $ │ 5 % │ 6 ^ │ 7 & │ 8 * │ 9 ( │ 0 ) │ - _ │ = + │Bksp │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│ Tab │  Q  │  W  │  E  │  R  │  T  │  Y  │  U  │  I  │  O  │  P  │ [ { │ ] } │ \ | │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│Caps │  A  │  S  │  D  │ F _ │  G  │  H  │ J _ │  K  │  L  │ ; : │ ' " │ Del │Enter│
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│Shift│  Z  │  X  │  C  │  V  │  B  │  N  │  M  │ , < │ . > │ / ? │Shift│  ↑  │PgUp │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│Ctrl │Super│ Alt │ Fn  │Lang │         Space (4-Col)       │ Alt │Ctrl │  ←  │  ↓  │ →/Dn│
└─────┴─────┴─────┴─────┴─────┴─────────────────────────────┴─────┴─────┴─────┴─────┴─────┘
  * Bottom-right cols 11–13 form a crisp Inverted-T Arrow cluster (↑ over ← ↓ →, with PgUp/PgDn adjacent; Fn+Arrows = Home/End/PgUp/PgDn).
```

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Custom Canvas Multi-Touch Engine vs. Android `InputMethodService` / Compose Grid**
  - *Chosen Approach*: Direct `Canvas` rendering with pre-allocated `KeyCell` geometry and per-pointer (`pointerId`) state tracking.
  - *Why*: Zero view-hierarchy inflation, zero GC allocations during fast 10-finger typing on the 1 GB Snapdragon 210, instant `<1ms` touch-to-packet dispatch, and independent multi-touch zoning (so resting a finger in Split-View trackpad while tapping a modifier in the keyboard pane never conflicts).
  - *Alternatives Considered*: Android soft keyboard (`InputMethodManager`) adds 40–100ms composition lag and lacks `Super`/`F1–F12`/`Sleep` keys; separate Compose `Button` composables allocate heavy state objects on rapid touch events.

- **Decision 2: Dual Virtual Kernel Devices (`uinput` Mouse + `uinput` Keyboard) on Linux Host**
  - *Chosen Approach*: `trackpad_host.py` registers two distinct `/dev/uinput` devices—one dedicated pointer device and one full keyboard/consumer/power device—with automatic stuck-key cleanup on disconnect.
  - *Why*: Ubuntu's `libinput` and `systemd-logind` classify standalone keyboard/power devices cleanly (`ID_INPUT_KEYBOARD=1`), ensuring native XKB layout switching (`Super+Space`), Wayland/X11 shortcut fidelity, media OSD popups, and reliable `KEY_SLEEP` / `KEY_POWER` handling without interfering with pointer acceleration curves.

- **Decision 3: Extending the Fixed 8-Byte Binary Protocol (`EVENT_KEY = 0x04`)**
  - *Chosen Approach*: Reuses the exact 8-byte frame (`0xAA`, `0x04`, `code = evdev_keycode`, `val1 = RELEASE(0)/PRESS(1)/CLICK(2)`, `val2 = modifier_mask`).
  - *Why*: Preserves 64-bit `Long` bit-packing in the lock-free sender queue with 100% backward compatibility for existing mouse/scroll frames.

---

## 4. Technical Architecture & Data Strategy

### System Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         Android Tablet Client                            │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                   Workstation Surface Controller                   │  │
│  │   [Mode State: TRACKPAD_ONLY | SPLIT_VIEW | FULL_KEYBOARD]         │  │
│  └───────────────┬────────────────────────────────────┬───────────────┘  │
│                  │ Pointer Router (by touch Y & ID)   │                  │
│                  ▼                                    ▼                  │
│  ┌───────────────────────────────┐    ┌───────────────────────────────┐  │
│  │     Trackpad Gesture Zone     │    │  Ortholinear Keyboard Engine  │  │
│  │ • 1F Move / Tap / DblTap Drag │    │ • 14x6 Pre-allocated Grid     │  │
│  │ • 2F Right-Click / Scroll     │    │ • Hold Chord + Sticky Latch   │  │
│  │ • Optional L/M/R Click Bar    │    │ • Repeat Timer + Power Guard  │  │
│  └───────────────┬───────────────┘    └───────────────┬───────────────┘  │
│                  │ EVENT_MOVE/BTN/SCROLL              │ EVENT_KEY (0x04) │
│                  └─────────────────┬──────────────────┘                  │
│                                    ▼                                     │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │     Async Lock-Free 64-bit Packet Queue -> TCP Server (:8081)      │  │
│  └─────────────────────────────────┬──────────────────────────────────┘  │
└────────────────────────────────────┼─────────────────────────────────────┘
                                     │ USB Cable (`adb forward tcp:8081`)
┌────────────────────────────────────┼─────────────────────────────────────┐
│                                    ▼                                     │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │              Linux Host Daemon (`trackpad_host.py`)                │  │
│  │  • Frame Sync & Unpack (`!BBhhh`)                                  │  │
│  │  • Active Pressed-Key Tracker (auto-releases all keys on unplug)   │  │
│  └───────────────┬────────────────────────────────────┬───────────────┘  │
│                  │ Pointer Events                     │ Scancode Events  │
│                  ▼                                    ▼                  │
│  ┌───────────────────────────────┐    ┌───────────────────────────────┐  │
│  │   /dev/uinput (Virtual Mouse) │    │ /dev/uinput (Virtual Keyboard)│  │
│  │   REL_X, REL_Y, WHEEL, BTNs   │    │ KEY_A..Z, F1..12, MEDIA, PWR  │  │
│  └───────────────────────────────┘    └───────────────────────────────┘  │
│                               Linux Host PC                              │
└──────────────────────────────────────────────────────────────────────────┘
```

### Data Model & State
- **Protocol Extension (`EVENT_KEY = 0x04`)**:
  - `code` (`int16`): Standard Linux `evdev` keycode (e.g., `KEY_ESC=1`, `KEY_F4=62`, `KEY_LEFTMETA=125`, `KEY_MUTE=113`, `KEY_VOLUMEDOWN=114`, `KEY_VOLUMEUP=115`, `KEY_PLAYPAUSE=164`, `KEY_SLEEP=142`, `KEY_POWER=116`).
  - `val1` (`int16`): `0` = Key Up, `1` = Key Down, `2` = Atomic Tap/Combo Click.
  - `val2` (`int16`): Bitmask of latched/chorded modifiers (`0x01=Ctrl`, `0x02=Shift`, `0x04=Alt`, `0x08=Super`) applied atomically around `STATE_CLICK` combos (such as `Lang` = `Super + Space` or one-shot latched shortcuts).
- **Modifier State Machine (per modifier: `Ctrl`, `Shift`, `Alt`, `Super`, `Fn`)**:
  - `OFF` → `HELD` (while finger is down; transitions to `LATCHED` on quick release `<250ms` if no chorded key was pressed, otherwise returns to `OFF`) → `LOCKED` (if tapped while `LATCHED`) → `OFF`.
- **Pointer-to-Key Map**: Fixed `IntArray` indexed by Android `pointerId` (`0..9`) storing which key cell each active finger currently holds, supporting fast multi-finger rollovers and chorded shortcuts without allocations.

### Interactive Component & State Mapping
- **Header Mode Switcher**: Cycles `ViewMode` (`TRACKPAD_ONLY` → `SPLIT_VIEW` → `FULL_KEYBOARD`), recalculating trackpad bounds and keyboard cell rectangles immediately via `onSizeChanged`/layout pass.
- **Multi-Touch Split Partitioning**: In `SPLIT_VIEW`, pointers landing above `keyboardTopY` are owned by the trackpad gesture engine; pointers landing below `keyboardTopY` are owned by the ortholinear keyboard engine—allowing simultaneous cursor movement and modifier holding (`Ctrl+Drag`, `Shift+Click`, `Alt+Scroll`).
- **Auto-Repeat Scheduler**: Lightweight `Handler` runnable triggered while repeatable keys (`Backspace`, `Del`, `Arrows`, `Space`, `Vol-`, `Vol+`) remain held, firing repeat pulses cleanly and halting immediately on finger lift, slide-off, or `ACTION_CANCEL`.
- **Documentation & Reference Sync**: Comprehensive `README.md` update detailing system architecture, every source component, the 8-byte binary wire format, gesture + ortholinear keyboard reference tables, host daemon upgrades, and `systemd` startup service administration.
