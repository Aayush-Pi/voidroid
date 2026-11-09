#!/usr/bin/env bash
set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
USER_SYSTEMD_DIR="$HOME/.config/systemd/user"

echo "[*] Detecting connected Android device..."

# Check if adb sees any device
if ! adb get-state 1>/dev/null 2>&1; then
    echo "[!] No authorized ADB device found."
    read -rp "Enter your phone's USB Vendor ID manually (e.g. 04e8 from 'lsusb'): " VENDOR_ID
else
    # Extract USB vendor ID directly from sysfs of connected adb device
    DEV_PATH=$(adb devices -l | awk '/device usb:/ {for(i=1;i<=NF;i++) if($i ~ /^usb:/) print $i}' | cut -d: -f2)
    if [ -n "$DEV_PATH" ]; then
        # Find corresponding sysfs vendor id
        VENDOR_ID=$(udevadm info -q property -p "/sys/bus/usb/devices/$DEV_PATH" 2>/dev/null | awk -F= '/ID_VENDOR_ID/{print $2}')
    fi

    # Fallback prompt if detection fails
    if [ -z "$VENDOR_ID" ]; then
        read -rp "Could not auto-detect vendor ID. Enter it manually (from lsusb): " VENDOR_ID
    else
        echo "[+] Detected Vendor ID: $VENDOR_ID"
    fi
fi

# Dynamically write the udev rule with the detected vendor ID using +=
echo "[*] Generating udev rule for vendor: $VENDOR_ID..."
RULE_CONTENT="ACTION==\"add\", SUBSYSTEM==\"usb\", ATTR{idVendor}==\"$VENDOR_ID\", TAG+=\"systemd\", ENV{SYSTEMD_USER_WANTS}+=\"voidroid-reverse.service\""
echo "$RULE_CONTENT" | sudo tee /etc/udev/rules.d/99-voidroid.rules > /dev/null

echo "[*] Setting up systemd services..."
mkdir -p "$USER_SYSTEMD_DIR"
cp "$REPO_DIR/system/adb-server.service" "$USER_SYSTEMD_DIR/"
cp "$REPO_DIR/system/voidroid.service" "$USER_SYSTEMD_DIR/"
cp "$REPO_DIR/system/voidroid-reverse.service" "$USER_SYSTEMD_DIR/"

systemctl --user daemon-reload

# Enable persistent background units
systemctl --user enable --now adb-server.service
systemctl --user enable --now voidroid.service
systemctl --user enable voidroid-reverse.service

sudo udevadm control --reload-rules
sudo udevadm trigger

echo "[✓] Voidroid background services configured and activated."
