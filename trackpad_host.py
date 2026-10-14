#!/usr/bin/env python3
"""
Zero-Latency USB Trackpad Host Daemon for Linux
Translates binary packets from an Android tablet over an ADB tunnel into native
kernel input events via python-evdev /dev/uinput.

Compatible with X11, Wayland, GNOME, KDE, Sway, Hyprland, and raw Linux consoles.
"""

import os
import sys
import time
import socket
import struct
import subprocess
import argparse

try:
    import evdev
    from evdev import UInput, ecodes
except ImportError:
    print("\n[!] Error: 'python-evdev' is not installed.")
    print("    Install it via your distribution's package manager:")
    print("      Debian/Ubuntu: sudo apt-get install python3-evdev")
    print("      Arch Linux:    sudo pacman -S python-evdev")
    print("      Fedora:        sudo dnf install python3-libevdev python3-evdev")
    print("      Or with pip:   pip3 install evdev\n")
    sys.exit(1)

# Protocol Constants
MAGIC_BYTE = 0xAA
EVENT_MOVE = 0x01
EVENT_BUTTON = 0x02
EVENT_SCROLL = 0x03
EVENT_PING = 0x00

# Button Codes
BTN_CODE_LEFT = 1
BTN_CODE_RIGHT = 2
BTN_CODE_MIDDLE = 3

# Button States
STATE_RELEASE = 0
STATE_PRESS = 1
STATE_CLICK = 2

PACKET_STRUCT = struct.Struct("!BBhhh")
PACKET_SIZE = PACKET_STRUCT.size  # 8 bytes


def setup_adb_forward(port: int) -> bool:
    """Configures adb port forwarding to tunnel TCP over USB."""
    try:
        res = subprocess.run(["adb", "version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode != 0:
            print("[!] Warning: 'adb' returned an error. Ensure android-tools is installed.")
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
        print("[!] Warning: 'adb' not found in PATH. Please ensure ADB is installed and port 8081 is forwarded.")
        return False


def create_uinput_device() -> UInput:
    """Creates a virtual mouse/trackpad device in the Linux kernel via /dev/uinput."""
    capabilities = {
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

    try:
        device = UInput(
            events=capabilities,
            name="Android Low-Latency USB Trackpad",
            vendor=0x18D1,   # Google / Android
            product=0x4EE7,  # Virtual Peripheral
            version=0x0100,
        )
        print(f"[+] Created virtual input device: {device.name} ({device.device.path})")
        return device
    except PermissionError:
        print("\n[!] Permission Denied opening /dev/uinput.")
        print("    To run without root/sudo:")
        print("    1. Add your user to the 'input' group:")
        print("       sudo usermod -aG input $USER")
        print("    2. Add udev rule for uinput:")
        print('       echo \'KERNEL=="uinput", GROUP="input", MODE="0660"\' | sudo tee /etc/udev/rules.d/99-uinput.rules')
        print("       sudo udevadm control --reload-rules && sudo udevadm trigger")
        print("    3. Log out and log back in, or run with 'sudo python3 trackpad_host.py'.\n")
        sys.exit(1)


def map_button_code(code: int) -> int:
    """Maps custom protocol button ID to Linux evdev button code."""
    if code == BTN_CODE_LEFT:
        return ecodes.BTN_LEFT
    elif code == BTN_CODE_RIGHT:
        return ecodes.BTN_RIGHT
    elif code == BTN_CODE_MIDDLE:
        return ecodes.BTN_MIDDLE
    return ecodes.BTN_LEFT


def run_daemon(host: str, port: int, speed_multiplier: float):
    """Main daemon loop: connects to Android tablet and injects input events."""
    print("==================================================")
    print("   Android USB Trackpad Host Daemon (Linux)      ")
    print("==================================================")
    print(f"Target: {host}:{port} | Multiplier: {speed_multiplier:.2f}x\n")

    setup_adb_forward(port)
    uinput_dev = create_uinput_device()

    while True:
        sock = None
        try:
            print(f"[i] Connecting to trackpad server at {host}:{port}...")
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(5.0)

            sock.connect((host, port))
            sock.settimeout(None)
            print(f"[✓] Connected successfully! Trackpad active. Touch tablet to move cursor.")

            raw_buffer = bytearray()
            packet_count = 0
            last_stat_time = time.time()

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

                    magic, ev_type, code, val1, val2 = PACKET_STRUCT.unpack(packet_bytes)
                    packet_count += 1

                    if ev_type == EVENT_MOVE:
                        dx = int(val1 * speed_multiplier)
                        dy = int(val2 * speed_multiplier)
                        if dx != 0 or dy != 0:
                            uinput_dev.write(ecodes.EV_REL, ecodes.REL_X, dx)
                            uinput_dev.write(ecodes.EV_REL, ecodes.REL_Y, dy)
                            uinput_dev.syn()

                    elif ev_type == EVENT_BUTTON:
                        btn = map_button_code(code)
                        if val1 == STATE_PRESS:
                            uinput_dev.write(ecodes.EV_KEY, btn, 1)
                            uinput_dev.syn()
                        elif val1 == STATE_RELEASE:
                            uinput_dev.write(ecodes.EV_KEY, btn, 0)
                            uinput_dev.syn()
                        elif val1 == STATE_CLICK:
                            uinput_dev.write(ecodes.EV_KEY, btn, 1)
                            uinput_dev.syn()
                            time.sleep(0.015)
                            uinput_dev.write(ecodes.EV_KEY, btn, 0)
                            uinput_dev.syn()

                    elif ev_type == EVENT_SCROLL:
                        v_scroll = val1
                        h_scroll = val2
                        if v_scroll != 0:
                            uinput_dev.write(ecodes.EV_REL, ecodes.REL_WHEEL, v_scroll)
                        if h_scroll != 0:
                            uinput_dev.write(ecodes.EV_REL, ecodes.REL_HWHEEL, h_scroll)
                        uinput_dev.syn()

                    elif ev_type == EVENT_PING:
                        pass

                now = time.time()
                if now - last_stat_time >= 10.0:
                    packet_count = 0
                    last_stat_time = now

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
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass

    uinput_dev.close()
    print("[+] Virtual input device closed. Goodbye.")


def main():
    parser = argparse.ArgumentParser(description="Zero-latency USB Trackpad daemon for Linux host")
    parser.add_argument("--host", default="127.0.0.1", help="Target host (default: 127.0.0.1 via ADB forward)")
    parser.add_argument("--port", type=int, default=8081, help="Target TCP port (default: 8081)")
    parser.add_argument("--multiplier", type=float, default=1.0, help="Cursor speed multiplier (default: 1.0)")
    args = parser.parse_args()

    run_daemon(args.host, args.port, args.multiplier)


if __name__ == "__main__":
    main()
