#!/usr/bin/env bash
# ==============================================================================
# Linux Host Setup Script for Android USB Trackpad
# Sets up uinput udev permissions, python-evdev, and ADB port forwarding.
# ==============================================================================

set -e

echo "=== Android USB Trackpad Host Setup ==="

# 1. Check Python 3
if ! command -v python3 &> /dev/null; then
    echo "[!] Python 3 is required. Please install python3 first."
    exit 1
fi

# 2. Check / Install python3-evdev
if ! python3 -c "import evdev" &> /dev/null; then
    echo "[*] python-evdev is not installed. Attempting installation..."
    if command -v apt-get &> /dev/null; then
        sudo apt-get update && sudo apt-get install -y python3-evdev
    elif command -v pacman &> /dev/null; then
        sudo pacman -S --noconfirm python-evdev
    elif command -v dnf &> /dev/null; then
        sudo dnf install -y python3-libevdev python3-evdev
    else
        pip3 install --user evdev
    fi
fi

# 3. Check ADB
if ! command -v adb &> /dev/null; then
    echo "[*] ADB (Android Debug Bridge) is not installed. Installing android-tools..."
    if command -v apt-get &> /dev/null; then
        sudo apt-get install -y adb
    elif command -v pacman &> /dev/null; then
        sudo pacman -S --noconfirm android-tools
    elif command -v dnf &> /dev/null; then
        sudo dnf install -y android-tools
    fi
fi

# 4. Configure /dev/uinput permissions for non-root execution
if [ ! -w /dev/uinput ]; then
    echo "[*] Configuring /dev/uinput permissions for current user..."
    sudo usermod -aG input "$USER" || true
    echo 'KERNEL=="uinput", GROUP="input", MODE="0660"' | sudo tee /etc/udev/rules.d/99-uinput.rules
    sudo udevadm control --reload-rules && sudo udevadm trigger
    sudo modprobe uinput || true
fi

# 5. Forward TCP port 8081 over ADB
echo "[*] Ensuring ADB daemon is running..."
adb start-server
echo "[*] Setting up port forward: localhost:8081 -> tablet:8081..."
adb forward tcp:8081 tcp:8081 || true

echo ""
echo "[✓] Setup complete!"
echo "Now start the trackpad daemon using:"
echo "    python3 trackpad_host.py"
echo ""
