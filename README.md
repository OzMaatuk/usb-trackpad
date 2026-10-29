# Zero-Latency USB Workstation: Trackpad & Ortholinear Pro Keyboard for Linux

Turn an Android tablet (even legacy 1 GB RAM hardware like the **Lenovo TAB 10 TB-X103F** running Android 7.1.2 Nougat / LineageOS 14) into a **sub-millisecond wired USB Trackpad and 14×5 Ortholinear Workstation Keyboard** for any Linux PC.

All communication runs offline over a standard USB cable via ADB TCP port forwarding (`TCP_NODELAY`) into native Linux kernel `/dev/uinput` virtual input devices—delivering hardware-grade responsiveness across **X11**, **Wayland**, GNOME, KDE Plasma, Sway, Hyprland, i3, and Linux TTYs.

---

## Key Features

- **3 Instant Workstation View Modes** *(persisted automatically)*:
  - **Split-View (`View: Split`)**: Multi-touch precision trackpad on top + 14×5 Ortholinear Pro Keyboard on the bottom.
  - **Full-Keyboard (`View: Keys`)**: Full-screen ortholinear keycaps for maximum two-hand touch-typing comfort (with optional top mouse click strip).
  - **Trackpad-Only (`View: Pad`)**: Full-screen precision trackpad surface.
  - **OLED Stealth Mode (`OLED`)**: Pure `#000000` full-screen trackpad with zero UI chrome for dark-room/power-saving use.
- **14×5 Symmetrical Ortholinear Pro Keyboard**:
  - Strictly **5 rows total** (1 unified Number/Fn strip directly above the 3 character rows + bottom modifier/navigation row) so keycaps stay tall, wide, and effortless to hit without looking.
  - Dedicated **`ESC`**, **`F1–F12`** (highlighting **`F4`** for window closing), **`Mute`**, **`Vol-`**, **`Vol+`**, **`Play/Pause`**, **`Sleep`**, and **`Off` (Shutdown/Power)** on the `Fn` layer.
  - Dedicated **`Lang`** key sending atomic **`Super + Space`** for instant OS keyboard language switching.
  - Quick **`✕Win`** (`Fn + Tab`) sending atomic **`Alt + F4`** to close the active window in one chord.
  - Tactile cyan homing bars on **`F`** and **`J`** plus inverted-T arrow cluster (`↑ ← ↓ →` / `PgUp Home PgDn End`).
- **Hybrid Pro Modifiers (Hold-to-Chord + Sticky Latch + Lock)**:
  - **Hold + Press/Click**: Hold `Ctrl`, `Alt`, `Shift`, `Super`, or `Fn` with one finger while tapping keys—or while scrolling/clicking/dragging on the trackpad (`Ctrl + 2F Scroll` to zoom, `Alt + Drag` to move windows, `Shift + Click` to range-select).
  - **Single-Tap Sticky ("One-Shot") Latch**: Tap any modifier (`< 260ms`) to latch it for the next key or click—ideal for one-handed shortcuts like `Tap Alt` → `Tap Fn` → `Tap F4` without finger gymnastics.
  - **Double-Tap Lock**: Double-tap `Shift` or `Fn` to lock the layer active (highlighted in amber) until tapped again.
- **Workstation Reliability & Safety**:
  - **Deterministic 26 Hz Typematic Auto-Repeat**: Holding `Bksp`, `Del`, `Space`, `Arrows`, `Vol-`, `Vol+`, or character keys triggers smooth auto-repeat (`360ms` delay, `38ms` interval) using atomic click frames—making "stuck repeating keys" on Linux impossible.
  - **2-Tap Power Safety Guard**: `Sleep` (`Fn + =`) and `Off` (`Fn + Bksp`) arm on the first tap (glowing red **`CONFIRM`** for `2.5s`) and only fire on the second confirmation tap, preventing accidental system sleep while typing `=` or `Backspace`.
  - **Zero-Allocation Render & Network Pipeline**: Custom `Canvas` view + primitive 64-bit `Long` ring buffer (`ArrayBlockingQueue`) ensure zero garbage-collection pauses and strict off-UI-thread socket I/O.

---

## System Architecture

```
 ┌──────────────────────────────────────────────────────────────────┐
 │               Android Tablet (USB Workstation App)               │
 │                                                                  │
 │                     TouchTrackpadView (Canvas)                   │
 │                  ┌───────────────┴───────────────┐               │
 │                  ▼                               ▼               │
 │     Per-Pointer Gesture Engine      OrtholinearKeyboardEngine    │
 │     (Move / Tap / Drag / 2F)        (14x5 Grid / Sticky / 26Hz)  │
 │                  └───────────────┬───────────────┘               │
 │                                  ▼ [64-bit Long Lock-Free Queue] │
 │           TrackpadPacketSenderThread (TCP :8081, TCP_NODELAY)    │
 └──────────────────────────────────┬───────────────────────────────┘
                                    │
                                    │ USB Cable (100% Offline)
                                    │ `adb forward tcp:8081 tcp:8081`
                                    ▼
 ┌──────────────────────────────────────────────────────────────────┐
 │                          Linux Host PC                           │
 │                                                                  │
 │              Python Host Daemon (`trackpad_host.py`)             │
 │              Unpacks 8-byte frames: `struct.Struct('!BBhhh')`    │
 │                  ┌───────────────┴───────────────┐               │
 │                  ▼                               ▼               │
 │   /dev/uinput Virtual Mouse       /dev/uinput Virtual Keyboard   │
 │   "Android Low-Latency            "Android Low-Latency           │
 │    USB Trackpad"                   Pro Keyboard"                 │
 └──────────────────────────────────────────────────────────────────┘
```

### Why Dual `/dev/uinput` Devices?
Linux `libinput` (used by GNOME, KDE, Sway, Hyprland, and Xorg) classifies input capabilities per device node. Exposing **two distinct kernel devices** ensures the OS applies proper pointer acceleration and scroll curves to `"Android Low-Latency USB Trackpad"` while treating `"Android Low-Latency Pro Keyboard"` as a genuine hardware keyboard with native XKB layout mapping and cross-device modifier chording (`Ctrl + Scroll`, `Super + Drag`, etc.).

---

## Usage Guide

### 1. Top Workstation HUD Controls
| Control Pill | Behavior |
|---|---|
| **`View: Split` / `View: Keys` / `View: Pad`** | Cycles between **Split-View**, **Full-Keyboard**, and **Trackpad-Only** modes. Saved automatically across launches. |
| **`Btns: ON` / `Btns: OFF`** | Shows or hides the physical `LEFT | MID | RIGHT` mouse button strip. |
| **`OLED`** | Enters pure `#000000` full-screen stealth trackpad mode. Tap the top-left indicator to exit. |
| **`−` / `+`** | Adjusts client-side cursor speed multiplier (`0.25x` – `4.0x`, saved automatically). |

### 2. Trackpad Gestures
| Gesture | Action | Linux Kernel Event |
|---|---|---|
| **1-Finger Move** | Relative cursor motion with sub-pixel remainder accumulation | `EV_REL` (`REL_X`, `REL_Y`) |
| **1-Finger Tap** | Left Click | `BTN_LEFT` |
| **1-Finger Double-Tap & Hold** | Click-and-Drag (move windows, highlight text) | `BTN_LEFT` Down → Move → Up |
| **2-Finger Tap** | Right Click | `BTN_RIGHT` |
| **2-Finger Vertical Drag** | Smooth vertical scroll wheel | `EV_REL` (`REL_WHEEL`) |
| **`LEFT` / `MID` / `RIGHT` Bar** | Dedicated tactile hold/click buttons (thumb-friendly) | `BTN_LEFT` / `BTN_MIDDLE` / `BTN_RIGHT` |

### 3. 14×5 Symmetrical Ortholinear Keyboard Map

Every row aligns cleanly across **14 uniform columns** (`5 rows` total — strictly **1 unified Number + Fn row** above the 3 character rows):

```
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ ` ~ │ F1! │ F2@ │ F3# │ F4$ │ F5% │Mute^│Vol-&│Vol+*│Play(│F11 )│F12 _│Sleep│ Off │
│ ESC │  1  │  2  │  3  │  4  │  5  │  6  │  7  │  8  │  9  │  0  │  -  │  =  │Bksp │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┤
│✕Win │     │     │     │     │     │ F6  │ F7  │ F8  │ F9  │ F10 │  {  │  }  │  |  │
│ Tab │  Q  │  W  │  E  │  R  │  T  │  Y  │  U  │  I  │  O  │  P  │  [  │  ]  │  \  │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┴─────┴─────┤
│     │     │     │     │     │     │     │     │     │     │  :  │  "  │           │
│Caps │  A  │  S  │  D  │ [F] │  G  │  H  │ [J] │  K  │  L  │  ;  │  '  │  Enter ↵  │
├─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┼─────┬─────┤
│     │     │     │     │     │     │     │     │  <  │  >  │  ?  │     │PgUp │PrtSc│
│Shift│  Z  │  X  │  C  │  V  │  B  │  N  │  M  │  ,  │  .  │  /  │Shift│  ↑  │ Del │
├─────┼─────┼─────┼─────┼─────┼─────┴─────┴─────┴─────┼─────┼─────┼─────┼─────┼─────┤
│Ctrl │Super│ Alt │ Fn  │Lang │         SPACE         │ Alt │ Fn  │Home │PgDn │ End │
│     │     │     │     │     │        (4-Wide)       │     │     │  ←  │  ↓  │  →  │
└─────┴─────┴─────┴─────┴─────┴───────────────────────┴─────┴─────┴─────┴─────┴─────┘
```

#### Essential Shortcuts & Layer Highlights
- **`ESC` & Backtick / Tilde**: Tap top-left `ESC` for Escape; `Shift + ESC` types `~`; `Fn + ESC` types `` ` ``.
- **Closing Windows (`Alt + F4`)**:
  - **Method 1 (Fast Macro)**: Tap or hold `Fn` + `Tab` (`✕Win`) to send `Alt + F4` in one shot.
  - **Method 2 (Standard `F4`)**: Hold or sticky-tap `Alt`, then tap `Fn` + `4` (`F4`).
- **Switching OS Input Language (`Lang`)**: Tap `Lang` (Row 4, Col 4) to emit an atomic `Super + Space` chord—switches languages on Ubuntu/GNOME/KDE while keeping hardware scancodes active on your Linux PC.
- **Media & Volume**: Hold or tap `Fn` + `6` (`Mute`), `7` (`Vol-`), `8` (`Vol+`), or `9` (`Play/Pause`). Holding `Fn + 7/8` smoothly repeats volume steps.
- **Sleep & Shutdown (`Sleep` / `Off`)**:
  1. Tap `Fn` + `=` (`Sleep`) or `Fn` + `Bksp` (`Off`). The keycap turns crimson and displays **`CONFIRM`** (`Fn` stays latched automatically).
  2. Tap the same key a second time within **2.5 seconds** to execute. Tapping any other key or waiting 2.5 seconds safely disarms it.

### 4. Modifier State Colors
- **Cyan Glow (`#00E5FF`)**: Modifier is physically **held** by a finger or **sticky-latched** for the next key/click.
- **Amber Gold (`#FFB300`)**: Modifier (`Shift`, `Fn`, or `Caps`) is **locked** active. Tap once to unlock.
- **Wayland/GNOME Super-Key Smart Guard**: Single-tapping `Super` to latch it does *not* immediately press `KEY_LEFTMETA` on the host; instead, it bundles `MOD_SUPER` atomically with the next key press so GNOME never mistakenly pops open the Activities Overview before you press your shortcut key.

---

## 8-Byte Binary Protocol Specification

Every mouse or keyboard action is serialized into a fixed **8-byte big-endian frame** (`!BBhhh` in Python `struct`):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Magic (0xAA)  |  Event Type   |         Code (int16)          |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          Val1 (int16)         |          Val2 (int16)         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| `event_type` | Name | `code` (int16) | `val1` (int16) | `val2` (int16) |
|---|---|---|---|---|
| `0x00` | `EVENT_PING` | `0` | `0` | `0` |
| `0x01` | `EVENT_MOVE` | `0` | `dx` (relative X) | `dy` (relative Y) |
| `0x02` | `EVENT_BUTTON` | `1`=Left, `2`=Right, `3`=Middle | `0`=Release, `1`=Press, `2`=Click | `0` |
| `0x03` | `EVENT_SCROLL` | `0` | `v_scroll` (`+1`/`-1`) | `h_scroll` |
| `0x04` | `EVENT_KEY` | Linux `evdev` keycode (e.g., `1`=`KEY_ESC`, `62`=`KEY_F4`, `142`=`KEY_SLEEP`) | `0`=Release, `1`=Press, `2`=Click | Transient Modifier Bitmask: `0x01`=Ctrl, `0x02`=Shift, `0x04`=Alt, `0x08`=Super |

---

## Codebase Walkthrough

| File | Role & Internal Design |
|---|---|
| `app/src/main/java/com/example/MainActivity.kt` | Lifecycle host: configures `StrictMode` monitoring, full-screen edge-to-edge immersive mode, `FLAG_KEEP_SCREEN_ON`, and starts/stops `TrackpadServer` cleanly across `onResume`/`onPause`. |
| `app/src/main/java/com/example/TouchTrackpadView.kt` | Unified bare-metal `View` managing responsive landscape/portrait HUD headers, `ViewMode` persistence (`SharedPreferences`), multi-touch gesture recognition, and **per-`pointerId` zone isolation** so trackpad and keyboard touches operate simultaneously without interference. |
| `app/src/main/java/com/example/OrtholinearKeyboardEngine.kt` | Zero-allocation 14×5 ortholinear matrix engine: pre-computes `RectF` key bounds on resize, handles multi-finger hit-testing, hybrid hold/sticky/lock modifier state machines, `26 Hz` `Handler` typematic repeat, `Lang`/`✕Win` combos, and 2-tap `Sleep`/`Off` arming. |
| `app/src/main/java/com/example/TrackpadServer.kt` | Packs 8-byte protocol frames into a single 64-bit `Long` (`packPacket`) pushed via `ArrayBlockingQueue.offer()` (zero heap allocation, never blocks the UI thread) to `TrackpadPacketSenderThread` over a `TCP_NODELAY` `ServerSocket` on port `8081`. |
| `app/src/main/java/com/example/LinuxKeyCodes.kt` | Maps Linux `<linux/input-event-codes.h>` scancodes (`KEY_ESC` through `KEY_PLAYPAUSE`, `KEY_SLEEP`, `KEY_POWER`), wire modifier bitmasks, and client virtual action codes. |
| `trackpad_host.py` | Linux Python 3 daemon: sets up `adb forward tcp:8081 tcp:8081`, creates virtual Mouse & Pro Keyboard `/dev/uinput` devices, unpacks frames with `<50µs` distinct `EV_SYN` taps (no `time.sleep` blocking), supports `--direct-power`, and releases all held keys/buttons automatically if USB disconnects. |
| `setup_host.sh` | Installs `python3-evdev` and `adb`, adds `$USER` to the `input` group, and writes `/etc/udev/rules.d/99-uinput.rules` for rootless `/dev/uinput` access. |
| `install_service.sh` | Generates, enables, and starts `~/.config/systemd/user/android-trackpad.service` so `trackpad_host.py` runs automatically on Ubuntu login/boot. |

---

## Setup & Installation

### 1. Linux Host Setup (Ubuntu / Debian / Mint)

Run the one-time setup script to install `python3-evdev`, `adb`, and `/dev/uinput` permissions:

```bash
chmod +x setup_host.sh
./setup_host.sh
```
*(If `setup_host.sh` added your user to the `input` group for the first time, log out and log back in once so group membership takes effect.)*

### 2. Build & Install the Android App

```bash
# Build debug APK
gradle assembleDebug

# Install onto USB-connected Android tablet (USB Debugging enabled)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch on tablet (or tap "USB Trackpad" on the home screen)
adb shell am start -n com.aistudio.usbtrackpad.pldkqw/com.example.MainActivity
```

### 3. Run the Linux Host Daemon Manually

```bash
python3 trackpad_host.py
```

#### Useful CLI Options (`trackpad_host.py`)
```bash
# Increase host-side cursor speed by 40%
python3 trackpad_host.py --multiplier 1.4

# Recommended for closed-lid / docked laptops (e.g., Acer Spin 5 on external monitor):
# Runs `systemctl suspend` / `systemctl poweroff -i` directly when Sleep/Off is confirmed
python3 trackpad_host.py --direct-power
```

### 4. Auto-Start on Ubuntu Boot (`systemd` User Service)

Install or update the background startup service with one command:

```bash
chmod +x install_service.sh
./install_service.sh
```

Or install with custom flags (for example, closed-lid laptop power handling + custom speed):

```bash
./install_service.sh --direct-power --multiplier 1.2
```

#### Updating After Pulling New `trackpad_host.py` Changes
If you already installed the service previously and updated `trackpad_host.py`, simply restart the service:

```bash
systemctl --user restart android-trackpad.service
```

#### Service Management Commands
```bash
# Check status
systemctl --user status android-trackpad.service

# Watch live connection events
journalctl --user -u android-trackpad.service -f

# Stop or disable startup service
systemctl --user stop android-trackpad.service
systemctl --user disable android-trackpad.service
```

---

## Closed-Lid / Clamshell Laptop Tips (Ubuntu)

If you use a convertible or laptop with its lid closed on an external monitor (e.g., Acer Spin 5):
1. **Sleep & Shutdown from Tablet**: Pass `--direct-power` to `trackpad_host.py` (or `./install_service.sh --direct-power`) so `Fn + =` (`Sleep` ×2) and `Fn + Bksp` (`Off` ×2) invoke `systemctl suspend` and `systemctl poweroff -i` even when GNOME inhibits `KEY_SLEEP` on closed lids.
2. **Waking from Sleep via USB**: Linux suspends ADB user-space sockets during S3/s2idle sleep, so use an external USB/Bluetooth mouse/keyboard click (with USB wake enabled in `/proc/acpi/wakeup`) or your monitor/dock power button to wake the laptop; once awake, `android-trackpad.service` reconnects the tablet automatically within ~2 seconds.
