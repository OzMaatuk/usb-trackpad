#!/usr/bin/env python3
"""
Zero-Latency USB Trackpad & Ortholinear Pro Keyboard Host Daemon for Linux
Translates compact 8-byte binary packets from an Android tablet over an ADB USB
tunnel into native kernel input events via python-evdev (/dev/uinput).

Registers two dedicated kernel virtual devices:
  1. "Android Low-Latency USB Trackpad" (EV_REL pointer + mouse buttons)
  2. "Android Low-Latency Pro Keyboard" (Full ANSI/ISO scancodes, F1-F12, Media, Sleep/Power)

Compatible with X11, Wayland, GNOME, KDE Plasma, Sway, Hyprland, and Linux TTYs.
"""

import sys
import time
import socket
import struct
import subprocess
import argparse

try:
    from evdev import UInput, ecodes
except ImportError:
    print("\n[!] Error: 'python-evdev' is not installed.")
    print("    Install it via your distribution's package manager:")
    print("      Debian/Ubuntu: sudo apt-get install python3-evdev")
    print("      Arch Linux:    sudo pacman -S python-evdev")
    print("      Fedora:        sudo dnf install python3-libevdev python3-evdev")
    print("      Or with pip:   pip3 install evdev\n")
    sys.exit(1)

# 8-Byte Frame Protocol Constants
MAGIC_BYTE = 0xAA
EVENT_PING = 0x00
EVENT_MOVE = 0x01
EVENT_BUTTON = 0x02
EVENT_SCROLL = 0x03
EVENT_KEY = 0x04

# Mouse Button IDs
BTN_CODE_LEFT = 1
BTN_CODE_RIGHT = 2
BTN_CODE_MIDDLE = 3

# Key / Button States
STATE_RELEASE = 0
STATE_PRESS = 1
STATE_CLICK = 2

# Atomic Modifier Bitmask (in val2 of EVENT_KEY)
MOD_CTRL = 0x01
MOD_SHIFT = 0x02
MOD_ALT = 0x04
MOD_SUPER = 0x08

MODIFIER_MAP = (
    (MOD_CTRL, ecodes.KEY_LEFTCTRL),
    (MOD_SHIFT, ecodes.KEY_LEFTSHIFT),
    (MOD_ALT, ecodes.KEY_LEFTALT),
    (MOD_SUPER, ecodes.KEY_LEFTMETA),
)

PACKET_STRUCT = struct.Struct("!BBhhh")
PACKET_SIZE = PACKET_STRUCT.size  # 8 bytes

# Supported Keyboard, Function, Navigation, Media & ACPI Power Keycodes
KEYBOARD_CAP_KEYS = sorted(
    set(range(ecodes.KEY_ESC, ecodes.KEY_DELETE + 1))
    | {
        ecodes.KEY_MUTE,
        ecodes.KEY_VOLUMEDOWN,
        ecodes.KEY_VOLUMEUP,
        ecodes.KEY_POWER,
        ecodes.KEY_LEFTMETA,
        ecodes.KEY_RIGHTMETA,
        ecodes.KEY_COMPOSE,
        ecodes.KEY_SLEEP,
        ecodes.KEY_WAKEUP,
        ecodes.KEY_NEXTSONG,
        ecodes.KEY_PLAYPAUSE,
        ecodes.KEY_PREVIOUSSONG,
        ecodes.KEY_STOPCD,
    }
)
VALID_KEY_SET = set(KEYBOARD_CAP_KEYS)


def setup_adb_forward(port: int) -> bool:
    """Configures adb port forwarding to tunnel TCP over USB."""
    try:
        res = subprocess.run(
            ["adb", "version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        )
        if res.returncode != 0:
            print("[!] Warning: 'adb' returned an error. Ensure android-tools-adb is installed.")
            return False

        cmd = ["adb", "forward", f"tcp:{port}", f"tcp:{port}"]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode == 0:
            print(f"[+] ADB port forward active: localhost:{port} -> tablet:{port}")
            return True
        else:
            print(f"[!] Warning: 'adb forward' failed: {res.stderr.strip()}")
            return False
    except FileNotFoundError:
        print("[!] Warning: 'adb' not found in PATH. Ensure ADB is installed and port 8081 is forwarded.")
        return False


def create_uinput_devices() -> tuple[UInput, UInput]:
    """Creates dedicated virtual Mouse and Pro Keyboard kernel devices via /dev/uinput."""
    mouse_caps = {
        ecodes.EV_REL: [
            ecodes.REL_X,
            ecodes.REL_Y,
            ecodes.REL_WHEEL,
            ecodes.REL_HWHEEL,
        ],
        ecodes.EV_KEY: [
            ecodes.BTN_LEFT,
            ecodes.BTN_RIGHT,
            ecodes.BTN_MIDDLE,
        ],
    }

    kbd_caps = {
        ecodes.EV_KEY: KEYBOARD_CAP_KEYS,
    }

    try:
        mouse_dev = UInput(
            events=mouse_caps,
            name="Android Low-Latency USB Trackpad",
            vendor=0x18D1,
            product=0x4EE7,
            version=0x0200,
        )
        kbd_dev = UInput(
            events=kbd_caps,
            name="Android Low-Latency Pro Keyboard",
            vendor=0x18D1,
            product=0x4EE8,
            version=0x0200,
        )
        print(f"[+] Virtual Mouse:    {mouse_dev.name} ({mouse_dev.device.path})")
        print(f"[+] Virtual Keyboard: {kbd_dev.name} ({kbd_dev.device.path})")
        return mouse_dev, kbd_dev
    except PermissionError:
        print("\n[!] Permission Denied opening /dev/uinput.")
        print("    To run without root/sudo:")
        print("    1. Add your user to the 'input' group:")
        print("       sudo usermod -aG input $USER")
        print("    2. Add udev rule for uinput:")
        print(
            '       echo \'KERNEL=="uinput", GROUP="input", MODE="0660"\' '
            "| sudo tee /etc/udev/rules.d/99-uinput.rules"
        )
        print("       sudo udevadm control --reload-rules && sudo udevadm trigger")
        print("    3. Log out and log back in, or run with 'sudo python3 trackpad_host.py'.\n")
        sys.exit(1)


def map_button_code(code: int) -> int:
    if code == BTN_CODE_LEFT:
        return ecodes.BTN_LEFT
    elif code == BTN_CODE_RIGHT:
        return ecodes.BTN_RIGHT
    elif code == BTN_CODE_MIDDLE:
        return ecodes.BTN_MIDDLE
    return ecodes.BTN_LEFT


def handle_key_event(
    kbd_dev: UInput,
    held_keys: set[int],
    keycode: int,
    state: int,
    mod_mask: int,
    direct_power: bool,
) -> None:
    """Injects keyboard, media, or power scancodes with optional atomic modifier chords."""
    if direct_power and state in (STATE_PRESS, STATE_CLICK):
        if keycode == ecodes.KEY_SLEEP:
            subprocess.Popen(["systemctl", "suspend"])
            return
        elif keycode == ecodes.KEY_POWER:
            subprocess.Popen(["systemctl", "poweroff", "-i"])
            return

    if keycode not in VALID_KEY_SET:
        return

    if state == STATE_PRESS:
        kbd_dev.write(ecodes.EV_KEY, keycode, 1)
        kbd_dev.syn()
        held_keys.add(keycode)

    elif state == STATE_RELEASE:
        kbd_dev.write(ecodes.EV_KEY, keycode, 0)
        kbd_dev.syn()
        held_keys.discard(keycode)

    elif state == STATE_CLICK:
        # Press any transient sticky/macro modifiers not already physically held
        transient_mods = []
        if mod_mask != 0:
            for bit, mod_code in MODIFIER_MAP:
                if (mod_mask & bit) and (mod_code not in held_keys):
                    kbd_dev.write(ecodes.EV_KEY, mod_code, 1)
                    transient_mods.append(mod_code)
            if transient_mods:
                kbd_dev.syn()

        # Distinct EV_SYN frames give zero-blocking sub-50us key tap across X11 & Wayland
        kbd_dev.write(ecodes.EV_KEY, keycode, 1)
        kbd_dev.syn()
        kbd_dev.write(ecodes.EV_KEY, keycode, 0)
        kbd_dev.syn()

        if transient_mods:
            for mod_code in reversed(transient_mods):
                kbd_dev.write(ecodes.EV_KEY, mod_code, 0)
            kbd_dev.syn()


def release_all_held(mouse_dev: UInput, kbd_dev: UInput, held_buttons: set[int], held_keys: set[int]) -> None:
    """Safety watchdog: releases any held mouse buttons or keyboard modifiers on disconnect."""
    if held_buttons:
        for btn in list(held_buttons):
            mouse_dev.write(ecodes.EV_KEY, btn, 0)
        mouse_dev.syn()
        held_buttons.clear()

    if held_keys:
        for key in list(held_keys):
            kbd_dev.write(ecodes.EV_KEY, key, 0)
        kbd_dev.syn()
        held_keys.clear()


def run_daemon(host: str, port: int, speed_multiplier: float, direct_power: bool):
    print("==========================================================")
    print("  Android USB Workstation Daemon (Trackpad + Keyboard)    ")
    print("==========================================================")
    print(f"Target: {host}:{port} | Speed: {speed_multiplier:.2f}x | DirectPower: {direct_power}\n")

    setup_adb_forward(port)
    mouse_dev, kbd_dev = create_uinput_devices()

    held_buttons: set[int] = set()
    held_keys: set[int] = set()

    while True:
        sock = None
        try:
            print(f"[i] Connecting to tablet workstation server at {host}:{port}...")
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(5.0)

            sock.connect((host, port))
            sock.settimeout(None)
            print("[✓] Connected! Trackpad & Ortholinear Pro Keyboard active.")

            raw_buffer = bytearray()

            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    print("\n[!] Connection closed by tablet.")
                    break

                raw_buffer.extend(chunk)

                while len(raw_buffer) >= PACKET_SIZE:
                    if raw_buffer[0] != MAGIC_BYTE:
                        idx = raw_buffer.find(bytes([MAGIC_BYTE]))
                        if idx == -1:
                            raw_buffer.clear()
                            break
                        else:
                            del raw_buffer[:idx]
                            if len(raw_buffer) < PACKET_SIZE:
                                break

                    packet_bytes = raw_buffer[:PACKET_SIZE]
                    del raw_buffer[:PACKET_SIZE]

                    _, ev_type, code, val1, val2 = PACKET_STRUCT.unpack(packet_bytes)

                    if ev_type == EVENT_MOVE:
                        dx = int(val1 * speed_multiplier)
                        dy = int(val2 * speed_multiplier)
                        if dx != 0 or dy != 0:
                            mouse_dev.write(ecodes.EV_REL, ecodes.REL_X, dx)
                            mouse_dev.write(ecodes.EV_REL, ecodes.REL_Y, dy)
                            mouse_dev.syn()

                    elif ev_type == EVENT_BUTTON:
                        btn = map_button_code(code)
                        if val1 == STATE_PRESS:
                            mouse_dev.write(ecodes.EV_KEY, btn, 1)
                            mouse_dev.syn()
                            held_buttons.add(btn)
                        elif val1 == STATE_RELEASE:
                            mouse_dev.write(ecodes.EV_KEY, btn, 0)
                            mouse_dev.syn()
                            held_buttons.discard(btn)
                        elif val1 == STATE_CLICK:
                            mouse_dev.write(ecodes.EV_KEY, btn, 1)
                            mouse_dev.syn()
                            mouse_dev.write(ecodes.EV_KEY, btn, 0)
                            mouse_dev.syn()

                    elif ev_type == EVENT_SCROLL:
                        if val1 != 0:
                            mouse_dev.write(ecodes.EV_REL, ecodes.REL_WHEEL, val1)
                        if val2 != 0:
                            mouse_dev.write(ecodes.EV_REL, ecodes.REL_HWHEEL, val2)
                        mouse_dev.syn()

                    elif ev_type == EVENT_KEY:
                        handle_key_event(kbd_dev, held_keys, code, val1, val2, direct_power)

                    elif ev_type == EVENT_PING:
                        pass

        except (socket.error, ConnectionRefusedError, socket.timeout):
            sys.stdout.write(f"\r[!] Waiting for tablet app on {host}:{port}... (Retrying in 2s)")
            sys.stdout.flush()
            time.sleep(2.0)
            setup_adb_forward(port)

        except KeyboardInterrupt:
            print("\n[i] Daemon stopped by user. Cleaning up...")
            break
        except Exception as e:
            print(f"\n[!] Unexpected error: {e}")
            time.sleep(2.0)
        finally:
            release_all_held(mouse_dev, kbd_dev, held_buttons, held_keys)
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass

    mouse_dev.close()
    kbd_dev.close()
    print("[+] Virtual input devices closed. Goodbye.")


def main():
    parser = argparse.ArgumentParser(
        description="Zero-latency USB Trackpad & Pro Keyboard daemon for Linux host"
    )
    parser.add_argument(
        "--host", default="127.0.0.1", help="Target host (default: 127.0.0.1 via ADB forward)"
    )
    parser.add_argument("--port", type=int, default=8081, help="Target TCP port (default: 8081)")
    parser.add_argument(
        "--multiplier", type=float, default=1.0, help="Host-side cursor speed multiplier (default: 1.0)"
    )
    parser.add_argument(
        "--direct-power",
        action="store_true",
        help="Execute 'systemctl suspend' / 'systemctl poweroff -i' directly on Sleep/Off keys "
        "(useful on closed-lid laptops where desktop environments ignore KEY_SLEEP)",
    )
    args = parser.parse_args()

    run_daemon(args.host, args.port, args.multiplier, args.direct_power)


if __name__ == "__main__":
    main()
