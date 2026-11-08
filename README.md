# Voidroid

Android notifications on your Linux desktop, over a USB cable. Click **Open App** on a notification to mirror just that app from your phone in its own window. Raw TCP sockets and ADB reverse tunneling. No cloud, no account, no third-party services.

> **Status:** `v0.4.0-alpha`. It works, the edges are rough. See the [roadmap](#roadmap) and the [changelog](#changelog).
>
> **Platform:** Linux only for now. Windows support is planned.

---

## Architecture Overview

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
   (mirrors only that app, phone screen stays off)
```

## Project Structure

```text
voidroid/
├── README.md
├── .gitignore
├── server/
│   └── notif.py                # Linux TCP listener & notification dispatcher
├── system/
│   ├── install.sh                  # Background service installer (run with bash)
│   ├── voidroid.service            # systemd user unit for the listener
│   ├── voidroid-reverse.service    # systemd user unit for `adb reverse`
│   └── 99-voidroid.rules           # Static udev rule (install.sh generates a vendor-specific one instead)
└── android/
    ├── gradlew
    ├── build.gradle
    └── app/
        └── src/main/java/com/example/voidroid/
            ├── MainActivity.kt        # Direct intent launcher for permission toggle
            └── NotificationService.kt # Notification listener & socket sender
```

## Prerequisites

**Host (Linux):**

- Python 3
- `adb` (Android Debug Bridge)
- `libnotify-bin` (`notify-send`)
- `scrcpy` **3.0 or newer** (needed for the Open App button, because it uses `--start-app`)

**Android device:**

- Android 8.0+ (API level 26+)
- USB Debugging enabled

On Debian/Ubuntu, install the basics:

```bash
sudo apt install adb libnotify-bin python3
```

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

> **Upgrading from an earlier release?** The wire format changed in `v0.4.0-alpha`, so the app and the daemon must both be updated together. An old app talking to the new `server/notif.py` (or the reverse) will not work. Pull the latest repo, install the new APK, and install scrcpy as above. If you use the [background service](#automatic-background-service), also run `systemctl --user restart voidroid.service` after pulling so the daemon runs the new code.

## Quick Start (Build From Source)

### 1. Build & Install Android Client

From the repository root:

```bash
git clone https://github.com/Aayush-Pi/voidroid.git
cd voidroid/android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the Voidroid stub on your phone and tap **Enable Notification Access** to grant listener permissions.

### 2. Forward the Port via ADB

Set up loopback routing from the Android device across the USB link to the host machine:

```bash
adb reverse tcp:5000 tcp:5000
```

### 3. Start the Linux Listener

Run the server daemon on your workstation:

```bash
cd ..
python3 server/notif.py
```

Any notification arriving on your device will now pop up natively on your desktop.

## Easy Install (No Build Required)

Don't want to build the app? Use the prebuilt APK from the [v0.4.0-alpha release](https://github.com/Aayush-Pi/voidroid/releases/tag/v0.4.0-alpha).

1. **Download the APK.** Open the release page on your phone (or download it on your computer) and grab the `.apk` file.
2. **Install it.** Either open the file on the phone and allow **Install unknown apps** when prompted, or install over USB:

   ```bash
   adb install -r path/to/downloaded.apk
   ```

3. **Grant access.** Open **Voidroid**, tap **Enable Notification Access**, and switch Voidroid on.
4. **Get the listener.**

   ```bash
   git clone https://github.com/Aayush-Pi/voidroid.git
   cd voidroid
   ```

5. **Install scrcpy 3.0 or newer** (see [Installing scrcpy](#installing-scrcpy)).
6. **Connect and run.** With USB debugging on and the phone plugged in:

   ```bash
   adb reverse tcp:5000 tcp:5000
   python3 server/notif.py
   ```

> **Android 13+:** if the notification access toggle is greyed out because the app was sideloaded, go to **Settings → Apps → Voidroid → ⋮ (top right) → Allow restricted settings**, then try again.

### Test it

With the listener running, send a fake notification from your computer:

```bash
adb shell cmd notification post -t "Voidroid test" voidroid "It works"
```

## Open App (Screen Mirroring)

Every desktop notification has an **Open App** button. Click it and the daemon starts scrcpy for the app that sent the notification:

```bash
scrcpy --start-app=<PackageName> --stay-awake --turn-screen-off --window-title="Voidroid: <AppName>"
```

- The app opens in its own scrcpy window, and you control it with your desktop keyboard and mouse.
- The phone's physical screen stays off.
- The button disappears after 7 seconds, along with the notification.
- Each notification is handled in its own thread, so waiting for a click never blocks the next notification from arriving.

### Limitations

- If an app has no launchable main activity (some internal system dialogs, for example), `scrcpy --start-app` may not be able to open that view.
- If the phone is locked with a PIN, pattern, or biometric, you need to unlock it once inside the scrcpy window before the app shows.
- Set Voidroid's battery optimization to **Unrestricted** in Android app settings. Some manufacturers' battery managers (One UI, MIUI and similar) otherwise put the notification listener to sleep.
- Requires scrcpy 3.0 or newer.

## Automatic Background Service

Run the listener as a background daemon that starts at login and sets up the ADB tunnel the moment your phone is plugged in over USB. No terminal needed.

### Install

Clone the repo to `~/voidroid` (the service file expects it there), plug in your phone with USB debugging enabled, then run:

```bash
bash system/install.sh
```

Use `bash`, not `sh`: the script relies on Bash features that plain `sh` or `dash` do not support.

The installer:

1. Detects your phone's USB vendor ID through `adb`. If detection fails, it asks you to type it in (find it with `lsusb`).
2. Writes a udev rule for that vendor to `/etc/udev/rules.d/99-voidroid.rules` (asks for `sudo`). The rule starts `voidroid-reverse.service` whenever that device is plugged in.
3. Copies `voidroid.service` and `voidroid-reverse.service` into `~/.config/systemd/user/`.
4. Enables and starts `voidroid.service`, so the listener runs in the background now and at every login. It restarts automatically if it crashes.
5. Reloads the udev rules.

From then on, plugging in the phone runs `adb reverse tcp:5000 tcp:5000` for you.

### Check status and logs

```bash
systemctl --user status voidroid.service
journalctl --user -u voidroid -f
```

If the tunnel does not come up after plugging in, check the other unit:

```bash
systemctl --user status voidroid-reverse.service
```

> If the background service is running, it already holds port 5000. Stop it with `systemctl --user stop voidroid.service` before running `python3 server/notif.py` by hand.

### Uninstall

```bash
systemctl --user disable --now voidroid.service
rm ~/.config/systemd/user/voidroid.service ~/.config/systemd/user/voidroid-reverse.service
sudo rm /etc/udev/rules.d/99-voidroid.rules
sudo udevadm control --reload-rules
```

## Protocol Specification

The Android client pushes a single UTF-8 stream per notification event over the loopback socket `127.0.0.1:5000`, tunneled to the host by `adb reverse tcp:5000 tcp:5000`:

```text
<PackageName>|<AppName>|<Title>|<MessageBody>
```

| Field | Meaning |
|-------|---------|
| `PackageName` | Android package ID, for example `com.whatsapp` or `org.telegram.messenger`. The host needs it to launch the exact app |
| `AppName` | Display name, for example `WhatsApp`. Used for the notification banner and icon |
| `Title` | The sender or group conversation title (`EXTRA_TITLE` or `EXTRA_CONVERSATION_TITLE`) |
| `MessageBody` | The message text, taken from `MessagingStyle` bundles, `EXTRA_TEXT_LINES`, or a big-text fallback |

- **Delimiter:** `|` separates the four fields.
- **Banner and icon:** the daemon sets the banner with `notify-send -a <AppName>` and resolves the icon with `notify-send -i <lowercase_app>`, so notifications use your system icon theme.
- **Open App:** the daemon passes `PackageName` to `scrcpy --start-app`, and shows `AppName` in the scrcpy window title.
- **Messaging chats:** for apps that use messaging-style notifications (WhatsApp, Telegram, Signal and similar), the client reads `Notification.EXTRA_MESSAGING_STYLE` (the `android.messages` array) and `EXTRA_TEXT_LINES`, and forwards the exact latest incoming message. Rapid messages in the same chat no longer collapse into a generic summary like "2 new messages".
- **Deduplication:** the client drops a notification only when the same message text arrives again within a 1000 ms window. Distinct consecutive messages from the same sender each get their own desktop notification, while group-summary and update spam is still filtered out.

> **Previous formats (no longer accepted):** `<AppName>|<Title>|<MessageBody>` in `v0.2.0-alpha` to `v0.3.0-alpha`, and `<DisplayTitle>|<MessageBody>` in `v0.1.0-alpha`.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Nothing appears on the desktop | Re-run `adb reverse tcp:5000 tcp:5000`. The tunnel drops whenever the cable is unplugged or `adb` restarts. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `adb devices` shows nothing | Try another cable or port, and make sure the USB mode isn't "charging only". |
| "Address already in use" on start | Another process holds port 5000. If you installed the background service, that is it: run `systemctl --user stop voidroid.service` first. Otherwise find it with `ss -ltnp \| grep 5000`. |
| Nothing appears after plugging in, with the background service installed | Check `systemctl --user status voidroid.service` and `voidroid-reverse.service`. Confirm `adb devices` lists the phone as `device`, and that the vendor ID in `/etc/udev/rules.d/99-voidroid.rules` matches `lsusb`. |
| Notification access toggle is greyed out | Use **Allow restricted settings** (see Easy Install). |
| Notifications stop after a while | Set Voidroid's battery optimization to **Unrestricted** in Android settings. |
| Garbled or missing banner and title after updating | The app and `server/notif.py` are from different releases. Update both to the same release. If you use the background service, restart it with `systemctl --user restart voidroid.service`. |
| Generic icon for an app | The daemon looks for an icon named after the lowercase app name (for example `whatsapp`). If your icon theme has none, the desktop shows its default icon. |
| Open App does nothing | Check that `scrcpy --version` reports 3.0 or newer and that `scrcpy` is on your `PATH`. Unlock the phone if it is locked, and check the daemon output (or `journalctl --user -u voidroid`) for scrcpy errors. |

## Changelog

### v0.4.0-alpha

- **Open App button:** notifications now carry an **Open App** action (7-second timeout). Clicking it launches `scrcpy --start-app=<PackageName>` so only that app is mirrored, with the phone screen off and keyboard and mouse control from the desktop.
- **Protocol:** wire format changed to `<PackageName>|<AppName>|<Title>|<MessageBody>`. The package name tells the host which app to launch. The app and daemon must be updated together.
- **Threading:** each notification is handled in its own thread, so waiting on a click or a timeout no longer blocks incoming notifications.
- **New dependency:** `scrcpy` 3.0 or newer on the host.

### v0.3.0-alpha

- **Background service:** `system/install.sh` installs a `systemd --user` service for the listener and a udev rule that runs `adb reverse` automatically when the phone is plugged in.
- **Android app:** unchanged since `v0.2.0-alpha`.

### v0.2.0-alpha

- **Protocol:** wire format changed from `<DisplayTitle>|<MessageBody>` to `<AppName>|<Title>|<MessageBody>`, so the daemon can set the app banner and resolve icons from the system theme.
- **Chat batching:** reads `EXTRA_MESSAGING_STYLE` / `android.messages` and `EXTRA_TEXT_LINES` to forward the exact latest message from WhatsApp, Telegram, Signal and similar apps.
- **Deduplication:** now compares the message text within a 1000 ms window instead of dropping the whole payload, so rapid distinct messages are no longer swallowed.
- **Daemon:** `server/notif.py` unpacks three fields and lowercases the app name for icon lookup. The app and daemon must be updated together.

### v0.1.0-alpha

- First release: Android notification listener, ADB reverse tunnel, and `notify-send` daemon.

## Roadmap

- [x] Linux systemd user service + udev auto-connect hook
- [x] Open App button on notifications, with on-demand app mirroring and device control through scrcpy
- [ ] More notification actions (dismiss, reply)
- [ ] Windows support (daemon, notifications, and an installer)
- [ ] Direct D-Bus integration (`org.freedesktop.Notifications`) to remove `notify-send` subshell overhead
- [ ] C implementation of the server daemon

## Contributing

Issues and pull requests are welcome. This is an early alpha, so bug reports with your Android version and desktop environment are especially useful.
