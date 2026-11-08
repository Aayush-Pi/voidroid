# Voidroid

Android notifications on your Linux desktop, over a USB cable. Raw TCP sockets and ADB reverse tunneling. No cloud, no account, no third-party services.

> **Status:** `v0.2.0-alpha`. It works, the edges are rough. See the [roadmap](#roadmap) and the [changelog](#changelog).

---

## Architecture Overview

```text
[ Android OS Notification Event ]
                │
                ▼
      NotificationService.kt
(Filters summaries, extracts app, sender and the latest message,
 including messaging-style chat batches)
                │
                ▼
       TCP Socket (127.0.0.1:5000)
                │
                ▼
   [ ADB Reverse Tunnel (USB) ]
                │
                ▼
       Linux Daemon (notif.py)
   (Binds 127.0.0.1:5000, parses stream)
                │
                ▼
    libnotify / notify-send (Desktop)
   (App banner + icon from the system theme)
```

## Project Structure

```text
voidroid/
├── README.md
├── .gitignore
├── server/
│   └── notif.py                # Linux TCP listener & notification dispatcher
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

**Android device:**

- Android 8.0+ (API level 26+)
- USB Debugging enabled

On Debian/Ubuntu:

```bash
sudo apt install adb libnotify-bin python3
```

> **Upgrading from v0.1.0-alpha?** The app and the daemon must both be updated together. The wire format changed in `v0.2.0-alpha`, so mixing an old app with the new `server/notif.py` (or the reverse) will not work. Pull the latest repo and install the new APK.

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

Don't want to build the app? Use the prebuilt APK from the [v0.2.0-alpha release](https://github.com/Aayush-Pi/voidroid/releases/tag/v0.2.0-alpha).

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

5. **Connect and run.** With USB debugging on and the phone plugged in:

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

## Protocol Specification

The Android client pushes a single UTF-8 stream per notification event:

```text
<AppName>|<Title>|<MessageBody>
```

| Field | Meaning |
|-------|---------|
| `AppName` | The app that posted the notification, for example `WhatsApp` |
| `Title` | The sender or group name |
| `MessageBody` | The message text |

- **Delimiter:** `|` separates the three fields.
- **Why three fields:** splitting the app from the title lets the daemon set the banner with `notify-send -a <AppName>` and resolve the icon with `notify-send -i <lowercase_app>`, so notifications use your system icon theme.
- **Daemon side:** `server/notif.py` unpacks the three fields and lowercases the app name for the native FreeDesktop/GTK icon lookup.
- **Messaging chats:** for apps that use messaging-style notifications (WhatsApp, Telegram, Signal and similar), the client reads `Notification.EXTRA_MESSAGING_STYLE` (the `android.messages` array) and `EXTRA_TEXT_LINES`, and forwards the exact latest incoming message. Rapid messages in the same chat no longer collapse into a generic summary like "2 new messages".
- **Deduplication:** the client drops a notification only when the same message text arrives again within a 1000 ms window. Distinct consecutive messages from the same sender each get their own desktop notification, while group-summary and update spam is still filtered out.

> **Previous format (v0.1.0-alpha):** `<DisplayTitle>|<MessageBody>`, where the display title resolved to `AppName • Sender`. This is no longer accepted.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Nothing appears on the desktop | Re-run `adb reverse tcp:5000 tcp:5000`. The tunnel drops whenever the cable is unplugged or `adb` restarts. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `adb devices` shows nothing | Try another cable or port, and make sure the USB mode isn't "charging only". |
| "Address already in use" on start | Another process holds port 5000. Find it with `ss -ltnp \| grep 5000`. |
| Notification access toggle is greyed out | Use **Allow restricted settings** (see Easy Install). |
| Notifications stop after a while | Exempt Voidroid from battery optimization in Android settings. |
| Garbled or missing banner and title after updating | The app and `server/notif.py` are from different releases. Update both to `v0.2.0-alpha`. |
| Generic icon for an app | The daemon looks for an icon named after the lowercase app name (for example `whatsapp`). If your icon theme has none, the desktop shows its default icon. |

## Changelog

### v0.2.0-alpha

- **Protocol:** wire format changed from `<DisplayTitle>|<MessageBody>` to `<AppName>|<Title>|<MessageBody>`, so the daemon can set the app banner and resolve icons from the system theme.
- **Chat batching:** reads `EXTRA_MESSAGING_STYLE` / `android.messages` and `EXTRA_TEXT_LINES` to forward the exact latest message from WhatsApp, Telegram, Signal and similar apps.
- **Deduplication:** now compares the message text within a 1000 ms window instead of dropping the whole payload, so rapid distinct messages are no longer swallowed.
- **Daemon:** `server/notif.py` unpacks three fields and lowercases the app name for icon lookup. The app and daemon must be updated together.

### v0.1.0-alpha

- First release: Android notification listener, ADB reverse tunnel, and `notify-send` daemon.

## Roadmap

- [ ] Linux systemd user service + udev auto-connect hook
- [ ] Click actions on desktop notifications (dismiss, open, reply)
- [ ] Direct D-Bus integration (`org.freedesktop.Notifications`) to remove `notify-send` subshell overhead
- [ ] Screen mirroring and device control by wrapping [scrcpy](https://github.com/Genymobile/scrcpy)
- [ ] C implementation of the server daemon

## Contributing

Issues and pull requests are welcome. This is an early alpha, so bug reports with your Android version and desktop environment are especially useful.
