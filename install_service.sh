#!/usr/bin/env bash
# ==============================================================================
# Auto-Installer for Android USB Trackpad systemd User Service (Ubuntu / Linux)
# Runs trackpad_host.py automatically on startup in the user's session.
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_SCRIPT="$SCRIPT_DIR/trackpad_host.py"
SERVICE_NAME="android-trackpad.service"
USER_SYSTEMD_DIR="$HOME/.config/systemd/user"

echo "=== Installing Android Trackpad Startup Service ==="

# 1. Verify trackpad_host.py exists
if [ ! -f "$HOST_SCRIPT" ]; then
    echo "[!] Error: Could not find trackpad_host.py at $HOST_SCRIPT"
    exit 1
fi
chmod +x "$HOST_SCRIPT"

# 2. Ensure /dev/uinput permissions are configured
if [ ! -w /dev/uinput ]; then
    echo "[*] Configuring /dev/uinput permissions..."
    sudo usermod -aG input "$USER" || true
    echo 'KERNEL=="uinput", GROUP="input", MODE="0660"' | sudo tee /etc/udev/rules.d/99-uinput.rules
    sudo udevadm control --reload-rules && sudo udevadm trigger
    sudo modprobe uinput || true
fi

# 3. Create user systemd directory
mkdir -p "$USER_SYSTEMD_DIR"

# 4. Generate the systemd service file with absolute path to this directory
PYTHON_BIN="$(which python3)"
SERVICE_DEST="$USER_SYSTEMD_DIR/$SERVICE_NAME"
EXTRA_ARGS="$*"

cat <<EOF > "$SERVICE_DEST"
[Unit]
Description=Android Low-Latency USB Workstation Daemon (Trackpad + Pro Keyboard)
After=network.target

[Service]
Type=simple
WorkingDirectory=$SCRIPT_DIR
ExecStart=$PYTHON_BIN "$HOST_SCRIPT" $EXTRA_ARGS
Restart=always
RestartSec=3
KillMode=process
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=default.target
EOF

echo "[+] Created service unit: $SERVICE_DEST"

# 5. Reload user systemd daemon, enable, and start service
echo "[*] Reloading systemd and enabling service..."
systemctl --user daemon-reload
systemctl --user enable "$SERVICE_NAME"
systemctl --user restart "$SERVICE_NAME"

echo ""
echo "=================================================="
echo " [✓] Android USB Trackpad Service is Active!"
echo "=================================================="
echo ""
echo "Useful Commands:"
echo "  Check status:   systemctl --user status $SERVICE_NAME"
echo "  View live logs: journalctl --user -u $SERVICE_NAME -f"
echo "  Restart:        systemctl --user restart $SERVICE_NAME"
echo "  Stop service:   systemctl --user stop $SERVICE_NAME"
echo "  Disable startup: systemctl --user disable $SERVICE_NAME"
echo ""
echo "Whenever your tablet is connected via Micro-USB, the service"
echo "will automatically bind, tunnel ADB, and act as your trackpad!"
