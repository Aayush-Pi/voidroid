# voidroid

Your Android notifications on your Linux desktop, over a USB cable. Click **Open App** on a notification to mirror just that app from your phone in its own window. voidroid uses a plain TCP socket through an `adb reverse` tunnel, plus scrcpy for mirroring. No cloud, no account, no third-party services.

> **Status:** `v0.6.2-beta.3` (beta). It works, but expect rough edges. **Beta testers are welcome**, see [Help test the beta](#help-test-the-beta).
>
> **Platform:** Linux only.

**Contents:** [Features](#features) · [How it works](#how-it-works) · [Install](#install) · [Requirements](#requirements) · [Using voidroid](#using-voidroid) · [Background service](#background-service) · [Reference](#reference) · [Troubleshooting](#troubleshooting) · [Updating](#updating) · [Help test the beta](#help-test-the-beta) · [Roadmap](#roadmap)

## Features

- Phone notifications appear as native desktop notifications, with the right app name and icon from your system theme
- Chat apps (WhatsApp, Telegram, Signal and similar) show the actual latest message, not a "2 new messages" summary
- Reliable delivery: the daemon acknowledges every notification and the app retries if it doesn't hear back
- **Open App** button on every notification: mirrors that app in a single scrcpy window, keeps your phone's screen off, and lets you use your keyboard and mouse
- Closing the mirror window locks the phone properly, so it wakes on the first button press
- Runs as a background service that starts at login, and the USB tunnel sets itself up when you plug the phone in
- Android app with a live permission status, a "Send test notification" button, a battery optimization shortcut, and an in-app debug log viewer
- Everything travels over the USB cable through `127.0.0.1`

## How it works

The Android app listens for notifications and sends each one to `127.0.0.1:5000` on the phone. `adb reverse` forwards that port through the USB cable to port 5000 on your laptop, where the daemon (`server/notif.py`) reads it, replies with an acknowledgement, shows the notification with `notify-send`, and adds an **Open App** button. Clicking the button starts scrcpy for that app.

```text
[ Android OS Notification Event ]
                │
                ▼
      NotificationService.kt
(Filters summaries, picks the latest message,
 tracks each notification so nothing is re-sent)
                │
                ▼
   TCP Socket (127.0.0.1:5000) ◄──── "OK" acknowledgement,
                │                    app retries up to 3 times
                ▼
   [ ADB Reverse Tunnel (USB) ]
                │
                ▼
       Linux Daemon (notif.py)
   (Parses payload, one thread per connection)
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

## Install

### 1. On the phone

**Enable Developer options and USB debugging first.** Then install the APK from the [latest release](https://github.com/Aayush-Pi/voidroid/releases/tag/v0.6.2-beta.3) using one of these two options.

**Option A (recommended): install over USB with adb**

```bash
adb install -r voidroid-v0.6.2-beta.3.apk
```

Some phones, with Google Play Protect's **Enhanced Fraud Protection** switched on (it can be on by default in some regions), block installing voidroid straight from the browser or a file manager. The reason is that voidroid asks to read your notifications. The error looks like *"This app can request access to sensitive data… App not installed"*, with no "Install anyway" option. Installing over USB with `adb` avoids this block and lets you keep Play Protect on.

**Option B: install on the phone itself**

1. Download `voidroid-v0.6.2-beta.3.apk` from the release page, and only from there.
2. Open **Google Play Store → profile icon → Play Protect → Settings (gear icon)** and turn off **Scan apps with Play Protect**.
3. Install the APK from your file manager or Downloads.
4. Turn **Scan apps with Play Protect** back on straight away. Play Protect will not remove an app that is already installed.

Then:

1. Open **voidroid** and tap the permission prompt to grant **Notification Access**.
2. Tap **Battery Optimization** and set voidroid to **Unrestricted** (or "Don't optimize"). Without this, many phones put the listener to sleep.

> **Android 13+:** if the Notification Access toggle is greyed out because the app was sideloaded, go to **Settings → Apps → voidroid → ⋮ (top right) → Allow restricted settings** first, then turn Notification Access on.

### 2. On the laptop

Install the requirements (Debian/Ubuntu shown, see [Requirements](#requirements) for other distros and for scrcpy), then clone the repo to `~/voidroid` and run the installer:

```bash
sudo apt install adb libnotify-bin python3
git clone https://github.com/Aayush-Pi/voidroid.git ~/voidroid
cd ~/voidroid
bash system/install.sh
```

### 3. Test it

Plug in the phone and tap **Send test notification** in the app. A notification should appear on your desktop. If it doesn't, see [Troubleshooting](#troubleshooting).

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

<details>
<summary><strong>Installing scrcpy 3.0 or newer</strong></summary>

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

</details>

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
- Each connection and each notification is handled in its own thread, so waiting for a click never blocks the next notification.

**Limitations**

- If an app has no launchable main activity (some internal system dialogs, for example), `scrcpy --start-app` may not be able to open that view.
- If the phone is locked with a PIN, pattern, or biometric, you need to unlock it once inside the scrcpy window before the app shows.
- **Open App** needs the package name, so it only works for notifications sent in the JSON format or the four-field legacy format (see [Wire protocol](#wire-protocol)).

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

<details>
<summary><strong>Running without the background service</strong></summary>

Plug in the phone and run:

```bash
adb reverse tcp:5000 tcp:5000
python3 server/notif.py
```

`adb reverse` is cleared when the cable is unplugged, so run the first command again after reconnecting.

</details>

<details>
<summary><strong>Uninstall</strong></summary>

```bash
systemctl --user disable --now voidroid.service adb-server.service
rm ~/.config/systemd/user/voidroid.service ~/.config/systemd/user/voidroid-reverse.service ~/.config/systemd/user/adb-server.service
sudo rm /etc/udev/rules.d/99-voidroid.rules
sudo udevadm control --reload-rules
```

</details>

<details>
<summary><strong>Building the Android app from source</strong></summary>

You do not need this if you installed the APK from a release.

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release builds are signed with a private keystore that is not in this repository, so use the debug build. A debug build has a different signature from the release APK, so Android will not let one update the other: uninstall the app first when switching between them.

</details>

## Reference

### Wire protocol

Each notification is sent as one UTF-8 JSON object, terminated by a NUL byte (`\0`):

```text
{"pkg": "<PackageName>", "app": "<AppName>", "title": "<Title>", "body": "<MessageBody>"}\0
```

| Field | Meaning |
|-------|---------|
| `pkg` | Android package ID, for example `com.whatsapp` or `org.telegram.messenger`. The host needs it to launch the exact app |
| `app` | Display name, for example `WhatsApp`. Used for the notification banner and icon |
| `title` | The sender or group conversation title |
| `body` | The message text |

- **Acknowledgement and retries:** after receiving a payload, `notif.py` replies `OK\n`. The app waits for it and retries up to 3 times (150 ms, then 300 ms backoff) on a socket error or a missing acknowledgement. This stops notifications being lost when Android closes the socket before `adbd` has finished setting up the reverse tunnel.
- **Special characters:** titles and bodies keep `|`, quotes, newlines, and Unicode exactly as they were. Nothing is stripped.
- **Safe display:** the daemon calls `notify-send` with `--` before the title and body, so text that starts with `-` is never read as a command-line flag.
- **Banner and icon:** the daemon sets the banner with `notify-send -a <AppName>` and resolves the icon with `notify-send -i <lowercase_app>`, so notifications use your system icon theme.

**Legacy format (still accepted)**

The daemon tries JSON first, then falls back to the older pipe-delimited formats, so an older app keeps working with a newer daemon:

```text
4 fields: <PackageName>|<AppName>|<Title>|<MessageBody>   (v0.4.0 to v0.6.1)
3 fields: <AppName>|<Title>|<MessageBody>                 (v0.2.0 to v0.3.0)
2 fields: <DisplayTitle>|<MessageBody>                    (v0.1.0)
1 field:  plain text
```

Legacy payloads cannot safely carry a `|` inside the text, which is why the JSON format replaced them. Only JSON and the four-field format include the package name that **Open App** needs.

### How the app picks what to send

- **Chat apps:** for apps that use messaging-style notifications, the app scans every message in `Notification.EXTRA_MESSAGES` and sends the one with the highest `time` stamp (the later one if two tie), instead of trusting array order. If the latest entry is a media attachment with no text, it falls back to `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, and finally the word "Attachment". `EXTRA_TEXT_LINES` is read too.
- **No re-spam:** the app remembers what it has sent for each individual notification and forgets it when that notification is dismissed. Unread conversations that WhatsApp or Android re-post are not sent again, and two different chats that happen to send identical text are both delivered.
- **Group summaries:** if a group summary carrying message data arrives and no individual notification from that group follows within 500 ms, the app sends the summary after a short 250 ms delay.

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
            ├── MainActivity.kt            # App screen: permission status, test notification, log viewer, shortcuts
            ├── NotificationService.kt     # Notification listener & socket sender
            └── NotificationLogStore.kt    # In-memory debug log (last 50 events)
```

### Logs and debugging

**On the laptop,** the daemon writes two log files:

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

**On the phone,** tap **View RAM logs** in the app. It shows the last 50 events, newest first: the listener connecting and disconnecting, why a notification was dropped or skipped, each send (`SENT (try=N)`), and socket errors. Use **Refresh**, **Copy**, or **Clear**. The log lives only in memory and is never written to storage, so it disappears when the app process ends. This means you can debug without `adb logcat`. Check what you copy for anything personal before sharing it.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Nothing appears on the desktop | Tap **Send test notification** in the app. If that fails, re-run `adb reverse tcp:5000 tcp:5000`. The tunnel drops whenever the cable is unplugged or `adb` restarts. Then check **View RAM logs** in the app and `daemon.log` on the laptop |
| A notification is missing | Open **View RAM logs** in the app. It shows whether the notification was dropped, skipped as a duplicate, or sent (`SENT (try=N)`), and whether there was a socket error |
| "App not installed" when installing the APK, mentioning sensitive data or fraud | Play Protect's Enhanced Fraud Protection is blocking the install. Install over USB with `adb install -r`, or pause Play Protect briefly (see [Install](#install)) |
| Two voidroid apps after updating | You updated from `v0.6.1-beta.2` or earlier. The app ID changed, so uninstall the old one (see [Updating](#updating)) |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt |
| `adb devices` shows nothing | Try another cable or port, and make sure the USB mode isn't "charging only" |
| "Address already in use" on start | Another process holds port 5000. If you installed the background service, that is it: run `systemctl --user stop voidroid.service` first. Otherwise find it with `ss -ltnp \| grep 5000` |
| Nothing appears after plugging in, with the background service installed | Check the status of `voidroid.service`, `voidroid-reverse.service`, and `adb-server.service`. Confirm `adb devices` lists the phone as `device`, and that the vendor ID in `/etc/udev/rules.d/99-voidroid.rules` matches `lsusb` |
| Tunnel drops right after plugging in | Make sure `adb-server.service` is enabled and running. Re-run `bash system/install.sh` if you upgraded from before `v0.5.0-alpha` |
| Notification Access toggle is greyed out | Use **Allow restricted settings** (see the Android 13+ note in [Install](#install)) |
| Notifications stop after a while | Set voidroid's battery optimization to **Unrestricted** in the app (**Battery Optimization** button) or in Android settings |
| Generic icon for an app | The daemon looks for an icon named after the lowercase app name (for example `whatsapp`). If your icon theme has none, the desktop shows its default icon |
| Open App does nothing | Check that `scrcpy --version` reports 3.0 or newer and that `scrcpy` is on your `PATH`. Unlock the phone if it is locked, and check `scrcpy.log` |
| scrcpy runs (you hear audio) but no window appears | Check `scrcpy.log`. This happened on Wayland when the background service launched scrcpy without the desktop session's display variables. It is fixed in `v0.5.0-alpha`, so update and restart the service |
| Phone needs two power-button presses to wake after closing the mirror window | Fixed in `v0.6.1-beta.2`. Update the repo and restart the service |
| Banner or title looks wrong after updating | The app and `server/notif.py` are from different releases. Update both, then run `systemctl --user restart voidroid.service` |

## Updating

1. `git pull` in your clone.
2. Install the new APK on the phone.
3. If you use the background service, re-run `bash system/install.sh` (it is safe to repeat) and then `systemctl --user restart voidroid.service` so the daemon runs the new code.

> **One-time step if you are on `v0.6.1-beta.2` or earlier:** the app ID changed from `com.example.voidroid` to `io.github.aayushpi.voidroid`. Android treats it as a different app, so installing the new APK would leave you with two copies. Uninstall the old one first, either in Android settings or with:
>
> ```bash
> adb uninstall com.example.voidroid
> ```
>
> You will need to grant Notification Access to the new app again. Future updates will install over the top as normal.

The daemon still understands messages from older apps as a fallback, but an older daemon does not understand the new JSON format. Update both together.

## Help test the beta

voidroid has been tested by 2 people so far, on 2 Linux setups and 4 phones (two running One UI, one running Funtouch OS, and one other). That is a small sample, so testers on other combinations are very welcome. All new features are done for the first stable release, so the beta is about finding bugs and gaps.

[Open an issue](https://github.com/Aayush-Pi/voidroid/issues) and include:

- Phone model and Android version
- Linux distribution, desktop environment, and Wayland or X11
- `scrcpy --version`
- What you expected, what happened, and the relevant lines from `daemon.log` or the app's **View RAM logs** (with personal text removed)

Reports that something worked are useful too, especially on phones from other brands and on distros other than Debian.

## Roadmap

- [x] Linux systemd user service + udev auto-connect hook
- [x] Open App button on notifications, with on-demand app mirroring and device control through scrcpy
- [ ] More notification actions (dismiss, reply)
- ❌ ~~Windows support~~ (dropped: voidroid stays Linux-only)
- [ ] Direct D-Bus integration (`org.freedesktop.Notifications`) to remove `notify-send` subshell overhead
- [ ] C implementation of the server daemon

## Release notes

**`v0.6.2-beta.3`** makes delivery more reliable and installs cleaner.

- **Breaking:** new app ID (`io.github.aayushpi.voidroid`), so uninstall `v0.6.1-beta.2` or earlier first. The APK is now signed with a release key.
- New JSON wire format with an acknowledgement and retries, so fewer notifications are dropped over USB. Special characters like `|` are no longer stripped. The old pipe-delimited format still works.
- Fixes for stacked and grouped chat notifications: no more re-spam, the correct latest message, and a fallback for group summaries.
- New **View RAM logs** screen in the app for debugging without `adb logcat`.
- New install guidance for phones where Play Protect blocks sideloading.

**`v0.6.1-beta.2`** fixes the phone needing two power-button presses after you close the mirror window.

**`v0.6.0-beta.1`** was the first beta, and the project became officially lowercase: **voidroid**.

Full history is in [CHANGELOG.md](CHANGELOG.md).

## Contributing

Issues and pull requests are welcome. This is an early project, so bug reports with your Android version and desktop environment are especially useful.
