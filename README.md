# voidroid

voidroid sends your Android notifications to your Linux desktop over a USB cable. Click **Open App** on a notification to mirror just that app from your phone in its own window. It uses a plain TCP socket through an `adb reverse` tunnel, plus scrcpy for mirroring. No cloud, no account, no third-party services.

> **Status:** `v0.6.1-beta.2` (beta). It works, but expect rough edges. See the [roadmap](#roadmap) and the [changelog](CHANGELOG.md).
>
> **Platform:** Linux only.

## Features

- Phone notifications appear as native desktop notifications, with the right app name and icon from your system theme
- Chat apps (WhatsApp, Telegram, Signal and similar) show the actual latest message, not a "2 new messages" summary
- **Open App** button on every notification: mirrors that app in a single scrcpy window, keeps your phone's screen off, and lets you use your keyboard and mouse
- Click **Open App** on another notification and the same window switches to that app
- Runs as a background service that starts at login, and the USB tunnel sets itself up when you plug the phone in
- Android app with a live permission status, a "Send test notification" button, and a shortcut to battery optimization settings
- Everything travels over the USB cable through `127.0.0.1`

## Quick start

### 1. On the phone

1. Enable Developer options and **USB debugging**.
2. Install the APK from the [latest release](https://github.com/Aayush-Pi/voidroid/releases/tag/v0.6.1-beta.2). Open the file on the phone and allow **Install unknown apps** when asked, or install over USB:

   ```bash
   adb install -r voidroid-v0.6.1-beta.2.apk
   ```

3. Open **voidroid** and tap the permission prompt to grant **Notification Access**.
4. Tap **Battery Optimization** and set voidroid to **Unrestricted** (or "Don't optimize"). Without this, many phones put the listener to sleep.

> **Android 13+:** if the Notification Access toggle is greyed out because the app was sideloaded, go to **Settings → Apps → voidroid → ⋮ (top right) → Allow restricted settings**, then try again.

### 2. On the laptop

Install the requirements (Debian/Ubuntu shown, see [Requirements](#requirements) for other distros and for scrcpy), then clone the repo to `~/voidroid` and run the installer:

```bash
sudo apt install adb libnotify-bin python3
git clone https://github.com/Aayush-Pi/voidroid.git ~/voidroid
cd ~/voidroid
bash system/install.sh
```

### 3. Test it

Plug in the phone and tap **Send test notification** in the app. A notification should appear on your desktop. If it doesn't, see [Troubleshooting](#troubleshooting-and-logs).

## Requirements

**Linux host**

- Python 3
- `adb` (Android Debug Bridge)
- `libnotify-bin` (`notify-send`)
- `scrcpy` **3.0 or newer** (the Open App button uses `--start-app`, which was added in 3.0)
- `systemd` user services, if you want the background service

**Android phone**

- Android 8.0+ (API level 26+)
- USB debugging enabled
- A USB cable that carries data, not a charge-only cable

### Installing scrcpy

Distro packages for scrcpy are often out of date, so check the version after installing:

```bash
scrcpy --version
```

It must report 3.0 or newer. Pick the route for your system:

| System | How to install |
|--------|----------------|
| Debian 13 | Needs the backports repository (see below) |
| Ubuntu and other Debian-based | The `apt` and `snap` packages are often old (scrcpy's own install notes mark them as outdated). Use the official static build or the install script below |
| Arch Linux | `sudo pacman -S scrcpy` |
| Fedora | Third-party Copr repository: `sudo dnf copr enable zeno/scrcpy && sudo dnf install scrcpy` |
| Gentoo | `sudo emerge scrcpy` |
| Any Linux | Download the static build from the [scrcpy releases page](https://github.com/Genymobile/scrcpy/releases/latest) and extract it, or use scrcpy's `install_release.sh` (see the [scrcpy Linux install guide](https://github.com/Genymobile/scrcpy/blob/master/doc/linux.md)) |

**Debian 13 (trixie) with backports:** scrcpy sits in the `contrib` component of trixie-backports, so the repository line must include `contrib`, not just `main`.

```bash
echo "deb http://deb.debian.org/debian trixie-backports main contrib" | sudo tee /etc/apt/sources.list.d/backports.list
sudo apt update
sudo apt install -t trixie-backports scrcpy
```

If `scrcpy --version` still reports something older than 3.0, use the static build instead.

## Using voidroid

Every desktop notification has an **Open App** button. Click it and the daemon starts scrcpy for the app that sent the notification:

```bash
scrcpy --start-app=<PackageName> --stay-awake --turn-screen-off --window-title="voidroid: <AppName>"
```

- The app opens in its own scrcpy window, and you control it with your desktop keyboard and mouse.
- The phone's physical screen stays off.
- The button disappears after 7 seconds, along with the notification.
- Only one mirror window is ever open. If you click **Open App** on another notification while a window is already running, the daemon does not start a second scrcpy. It switches the existing session to that app with `adb shell monkey -p <PackageName> -c android.intent.category.LAUNCHER 1`, so the video stream keeps running.
- When you close the mirror window, the daemon waits 300 ms for scrcpy to release the display and then puts the phone to sleep (`adb shell input keyevent KEYCODE_SLEEP`). The phone ends up properly locked, so the first press of the power button or fingerprint sensor wakes it.
- Each notification is handled in its own thread, so waiting for a click never blocks the next notification.

**Limitations**

- If an app has no launchable main activity (some internal system dialogs, for example), `scrcpy --start-app` may not be able to open that view.
- If the phone is locked with a PIN, pattern, or biometric, you need to unlock it once inside the scrcpy window before the app shows.
- Needs scrcpy 3.0 or newer.

## Background service

The installer sets voidroid up as a background service that starts at login and builds the USB tunnel the moment you plug your phone in.

```bash
bash system/install.sh
```

Run it from a clone at `~/voidroid` (the service file expects the repo there). Use `bash`, not `sh`: the script relies on Bash features that plain `sh` or `dash` do not support.

The installer:

1. Detects your phone's USB vendor ID through `adb`. If detection fails, it asks you to type it in (find it with `lsusb`).
2. Writes a udev rule for that vendor to `/etc/udev/rules.d/99-voidroid.rules` (asks for `sudo`). The rule starts `voidroid-reverse.service` whenever that device is plugged in.
3. Copies three units into `~/.config/systemd/user/` and enables them (table below).
4. Reloads the udev rules.

| Unit | What it does |
|------|--------------|
| `adb-server.service` | Keeps one `adb` server running (port 5037) for the whole desktop session. Without it, the short-lived reverse service would start its own temporary `adb` server and take it down on exit, dropping the tunnel |
| `voidroid.service` | Runs the listener (`server/notif.py`) in the background and restarts it if it crashes |
| `voidroid-reverse.service` | Runs `adb reverse tcp:5000 tcp:5000` when the phone is plugged in |

Check that everything is running:

```bash
systemctl --user status voidroid.service
systemctl --user status voidroid-reverse.service
systemctl --user status adb-server.service
```

> If the background service is running, it already holds port 5000. Stop it with `systemctl --user stop voidroid.service` before running `python3 server/notif.py` by hand.

**Uninstall**

```bash
systemctl --user disable --now voidroid.service adb-server.service
rm ~/.config/systemd/user/voidroid.service ~/.config/systemd/user/voidroid-reverse.service ~/.config/systemd/user/adb-server.service
sudo rm /etc/udev/rules.d/99-voidroid.rules
sudo udevadm control --reload-rules
```

### Running without the background service

If you would rather run things by hand, plug in the phone and run:

```bash
adb reverse tcp:5000 tcp:5000
python3 server/notif.py
```

`adb reverse` is cleared when the cable is unplugged, so run the first command again after reconnecting.

### Building the Android app from source

You do not need this if you installed the APK from a release.

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## How it works

The Android app listens for notifications and sends one line per notification to `127.0.0.1:5000` on the phone. `adb reverse` forwards that port through the USB cable to port 5000 on your laptop, where the daemon (`server/notif.py`) reads the line, shows it with `notify-send`, and adds an **Open App** button. Clicking the button starts scrcpy for that app.

```text
[ Android OS Notification Event ]
                │
                ▼
      NotificationService.kt
(Filters summaries, extracts package, app, sender and the latest
 message, including messaging-style chat batches)
                │
                ▼
       TCP Socket (127.0.0.1:5000)
                │
                ▼
   [ ADB Reverse Tunnel (USB) ]
                │
                ▼
       Linux Daemon (notif.py)
   (Binds 127.0.0.1:5000, parses stream,
    handles each notification in its own thread)
                │
                ▼
    libnotify / notify-send (Desktop)
   (App banner, icon, "Open App" button)
                │
                │  click "Open App"
                ▼
   scrcpy --start-app=<PackageName>
   (one window, phone screen stays off)
```

### Protocol

The Android client pushes a single UTF-8 stream per notification:

```text
<PackageName>|<AppName>|<Title>|<MessageBody>
```

| Field | Meaning |
|-------|---------|
| `PackageName` | Android package ID, for example `com.whatsapp` or `org.telegram.messenger`. The host needs it to launch the exact app |
| `AppName` | Display name, for example `WhatsApp`. Used for the notification banner and icon |
| `Title` | The sender or group conversation title (`EXTRA_TITLE` or `EXTRA_CONVERSATION_TITLE`) |
| `MessageBody` | The message text, taken from `MessagingStyle` bundles, `EXTRA_TEXT_LINES`, or a big-text fallback |

- **Banner and icon:** the daemon sets the banner with `notify-send -a <AppName>` and resolves the icon with `notify-send -i <lowercase_app>`, so notifications use your system icon theme.
- **Messaging chats:** for apps that use messaging-style notifications, the client reads `Notification.EXTRA_MESSAGING_STYLE` (the `android.messages` array) and `EXTRA_TEXT_LINES`, and forwards the exact latest incoming message.
- **Deduplication:** the client drops a notification only when the same message text arrives again within a 1000 ms window. Distinct consecutive messages from the same sender each get their own desktop notification, while group-summary and update spam is still filtered out.
- **Older formats:** the daemon also accepts three-field, two-field, and plain-text messages as a fallback. **Open App** needs the package name, so it only works for four-field messages.

### Project structure

```text
voidroid/
├── README.md
├── CHANGELOG.md
├── .gitignore
├── server/
│   └── notif.py                    # Linux TCP listener & notification dispatcher
├── system/
│   ├── install.sh                  # Background service installer (run with bash)
│   ├── adb-server.service          # systemd user unit that keeps the adb server running
│   ├── voidroid.service            # systemd user unit for the listener
│   ├── voidroid-reverse.service    # systemd user unit for `adb reverse`
│   └── 99-voidroid.rules           # Static udev rule (install.sh generates a vendor-specific one instead)
└── android/
    ├── gradlew
    ├── build.gradle
    └── app/
        └── src/main/java/com/example/voidroid/
            ├── MainActivity.kt        # App screen: permission status, test notification, shortcuts
            └── NotificationService.kt # Notification listener & socket sender
```

## Troubleshooting and logs

The daemon writes two log files:

| File | Contents |
|------|----------|
| `~/.cache/voidroid/daemon.log` | Daemon start-up, socket binds, received notification payloads, action clicks, and errors |
| `~/.cache/voidroid/scrcpy.log` | scrcpy's stdout and stderr, for display or encoder problems |

```bash
tail -f ~/.cache/voidroid/daemon.log
tail -f ~/.cache/voidroid/scrcpy.log
journalctl --user -u voidroid -f
```

> `daemon.log` includes the titles and text of your notifications. It stays on your machine, but remove anything sensitive before pasting it into a bug report.

| Symptom | Fix |
|---------|-----|
| Nothing appears on the desktop | Tap **Send test notification** in the app. If that fails, re-run `adb reverse tcp:5000 tcp:5000`. The tunnel drops whenever the cable is unplugged or `adb` restarts |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt |
| `adb devices` shows nothing | Try another cable or port, and make sure the USB mode isn't "charging only" |
| "Address already in use" on start | Another process holds port 5000. If you installed the background service, that is it: run `systemctl --user stop voidroid.service` first. Otherwise find it with `ss -ltnp \| grep 5000` |
| Nothing appears after plugging in, with the background service installed | Check the status of `voidroid.service`, `voidroid-reverse.service`, and `adb-server.service`. Confirm `adb devices` lists the phone as `device`, and that the vendor ID in `/etc/udev/rules.d/99-voidroid.rules` matches `lsusb` |
| Tunnel drops right after plugging in | Make sure `adb-server.service` is enabled and running. Re-run `bash system/install.sh` if you upgraded from before `v0.5.0-alpha` |
| Notification Access toggle is greyed out | Use **Allow restricted settings** (see the Android 13+ note in the quick start) |
| Notifications stop after a while | Set voidroid's battery optimization to **Unrestricted** in the app (**Battery Optimization** button) or in Android settings |
| Generic icon for an app | The daemon looks for an icon named after the lowercase app name (for example `whatsapp`). If your icon theme has none, the desktop shows its default icon |
| Open App does nothing | Check that `scrcpy --version` reports 3.0 or newer and that `scrcpy` is on your `PATH`. Unlock the phone if it is locked, and check `scrcpy.log` |
| scrcpy runs (you hear audio) but no window appears | Check `scrcpy.log`. This happened on Wayland when the background service launched scrcpy without the desktop session's display variables. It is fixed in `v0.5.0-alpha`, so update and restart the service |
| Phone needs two power-button presses to wake after closing the mirror window | Fixed in `v0.6.1-beta.2`. Update the repo and restart the service. The daemon now puts the phone to sleep when the scrcpy window closes |
| Banner or title looks wrong after updating | The app and `server/notif.py` are from different releases. Update both, then run `systemctl --user restart voidroid.service` |

## Updating

1. `git pull` in your clone.
2. Install the new APK on the phone.
3. If you use the background service, re-run `bash system/install.sh` (it is safe to repeat) and then `systemctl --user restart voidroid.service` so the daemon runs the new code.

The daemon still understands messages from older apps as a fallback, but an older daemon may not understand a newer app. Update both together.

## Roadmap

- [x] Linux systemd user service + udev auto-connect hook
- [x] Open App button on notifications, with on-demand app mirroring and device control through scrcpy
- [ ] More notification actions (dismiss, reply)
- ❌ ~~Windows support~~ (dropped: voidroid stays Linux-only)
- [ ] Direct D-Bus integration (`org.freedesktop.Notifications`) to remove `notify-send` subshell overhead
- [ ] C implementation of the server daemon

## Release notes

**`v0.6.1-beta.2`** fixes the phone needing two power-button presses after you close the mirror window. voidroid now puts the phone to sleep when the scrcpy window closes, so the first press wakes it.

**`v0.6.0-beta.1`** was the first beta, and the project became officially lowercase: **voidroid**.

- Redesigned Android app with a new icon, a dark theme, a live Notification Access indicator, a **Send test notification** button, a battery optimization shortcut, and a **View on GitHub** button
- Single mirror window with instant app switching, a persistent `adb` server, Wayland fixes, and log files (from `v0.5.0-alpha`)

Full history is in [CHANGELOG.md](CHANGELOG.md).

## Contributing

Issues and pull requests are welcome. This is an early project, so bug reports with your Android version and desktop environment are especially useful.
