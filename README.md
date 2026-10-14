# Zero-Latency USB Trackpad for Linux Host (Android 7.1.2 / Snapdragon 210)

High-precision, sub-millisecond USB trackpad for Linux host computers using a legacy Android tablet (e.g. Lenovo TAB 10 TB-X103F, LineageOS 14, Android 7.1.2 Nougat, 1 GB RAM) connected exclusively over an offline Micro-USB cable via ADB port forwarding.

---

## Architecture Overview

```
 ┌──────────────────────────────────────────────┐
 │     Android Tablet (Lenovo TAB 10 / Nougat)   │
 │                                              │
 │   TouchTrackpadView (Pure #000000 Canvas)    │
 │       │                                      │
 │       ▼ [Low-level MotionEvent Capture]      │
 │   Gesture Engine                             │
 │       │                                      │
 │       ▼ [Fixed 8-Byte Binary Packet]         │
 │   TrackpadServer (TCP :8081, TCP_NODELAY)    │
 └──────────────────────┬───────────────────────┘
                        │
                        │ Micro-USB (Offline Cable)
                        │ Tunnel: `adb forward tcp:8081 tcp:8081`
                        ▼
 ┌──────────────────────────────────────────────┐
 │             Linux Host PC                    │
 │                                              │
 │   Python Daemon (`trackpad_host.py`)         │
 │       │ Connects to localhost:8081           │
 │       ▼ [struct.unpack('!BBhhh')]            │
 │   python-evdev UInput Controller             │
 │       │                                      │
 │       ▼ [ioctl]                              │
 │   /dev/uinput (Linux Kernel Input Device)    │
 └──────────────────────────────────────────────┘
```

- **Zero GPU Overhead**: Renders on a pure black (`#000000`) canvas. No background window compositions or layout thrashing.
- **Zero GC Allocation in Touch Loop**: All binary packets use pre-allocated static byte buffers and primitive math, preventing Android Dalvik/ART GC pauses on 1 GB RAM devices.
- **Sub-Millisecond Transport**: Employs TCP loopback over USB via `adb forward tcp:8081 tcp:8081`, with `TCP_NODELAY` enabled on both ends to disable Nagle's buffering algorithm.
- **Universal Linux Compatibility**: Injects native mouse events via kernel `/dev/uinput`. Compatible with **X11**, **Wayland**, GNOME, KDE Plasma, Sway, Hyprland, i3, and Linux text consoles.

---

## 8-Byte Binary Protocol

Every touch update streams a compact 8-byte big-endian packet:

```
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| Magic (0xAA)  |  Event Type   |           Code                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|             Val1              |           Val2                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Field | Size | Details |
|---|---|---|
| `magic` | 1 byte | Fixed synchronization byte (`0xAA`) |
| `event_type` | 1 byte | `0x01` = MOVE, `0x02` = BUTTON, `0x03` = SCROLL, `0x00` = PING |
| `code` | 2 bytes | Button ID (`1` = Left, `2` = Right, `3` = Middle) |
| `val1` | 2 bytes | `dx` for MOVE; `state` (`0`=Up, `1`=Down, `2`=Click) for BUTTON; `v_scroll` for SCROLL |
| `val2` | 2 bytes | `dy` for MOVE; `h_scroll` for SCROLL |

---

## Gesture Engine Guide

| Gesture | Action | Host Linux Event |
|---|---|---|
| **1-Finger Drag** | Move cursor | `REL_X`, `REL_Y` relative deltas |
| **1-Finger Quick Tap** | Left Click | `BTN_LEFT` (Press + Release) |
| **2-Finger Quick Tap** | Right Click | `BTN_RIGHT` (Press + Release) |
| **2-Finger Vertical Drag** | Smooth Scroll Wheel | `REL_WHEEL` |
| **1-Finger Double-Tap & Hold** | Click-and-Drag | `BTN_LEFT` Down + Move (Window drag / Text select) |
| **Bottom Physical Buttons** | Left, Middle, Right | Dedicated bottom buttons for thumb clicks |

---

## Linux Host Quick Start

### 1. One-Click Setup
Run the included setup script on your Linux machine:
```bash
chmod +x setup_host.sh
./setup_host.sh
```

### 2. Manual Setup (Debian / Ubuntu / Mint)
```bash
# Install dependencies
sudo apt-get update
sudo apt-get install -y python3-evdev adb

# Allow non-root access to /dev/uinput
sudo usermod -aG input $USER
echo 'KERNEL=="uinput", GROUP="input", MODE="0660"' | sudo tee /etc/udev/rules.d/99-uinput.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
sudo modprobe uinput

# Note: If usermod was just run, log out and log back in, or run with `sg input`
```

### 3. Connect Tablet and Start Daemon
1. Plug the tablet into your Linux PC via Micro-USB.
2. Verify ADB connection:
   ```bash
   adb devices
   ```
3. Forward the port:
   ```bash
   adb forward tcp:8081 tcp:8081
   ```
4. Launch the daemon:
   ```bash
   python3 trackpad_host.py
   ```
   *(Optional arguments: `--multiplier 1.5` for higher sensitivity, `--port 8081`)*

---

## Android App Build & Installation

### Building the APK
```bash
gradle assembleRelease
# or debug APK:
gradle assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Installing on the Tablet via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Launching the App
Open **"USB Trackpad"** from the tablet launcher, or run:
```bash
adb shell am start -n com.aistudio.usbtrackpad.pldkqw/com.example.MainActivity
```

---

## Deliverables in Repository

- `trackpad_host.py`: Standalone Linux host daemon using `python-evdev` and `uinput`.
- `setup_host.sh`: Automated dependency installation and udev rule generator.
- `app/src/main/java/com/example/MainActivity.kt`: Android Activity managing full-screen immersive mode, screen wake-lock, and lifecycle.
- `app/src/main/java/com/example/TouchTrackpadView.kt`: High-performance touch surface with gesture engine and pure black canvas.
- `app/src/main/java/com/example/TrackpadServer.kt`: Dedicated TCP ServerSocket background thread streaming 8-byte binary packets.
- `java_reference/MainActivity.java`: Complete pure Java reference for standalone zero-dependency legacy builds.
