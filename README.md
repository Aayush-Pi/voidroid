# Voidroid

Android notifications on your Linux desktop, over a USB cable. Raw TCP sockets and ADB reverse tunneling. No cloud, no account, no third-party services.

> **Status:** alpha. It works, the edges are rough. See the [roadmap](#roadmap).

---

## Architecture Overview

```text
[ Android OS Notification Event ]
                │
                ▼
      NotificationService.kt
(Filters summaries, extracts human-readable app & sender)
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

Don't want to build the app? Use the prebuilt APK from the [v0.1.0-alpha release](https://github.com/Aayush-Pi/voidroid/releases/tag/v0.1.0-alpha).

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
<DisplayTitle>|<MessageBody>
```

- **Delimiter:** a single `|` separates title from body.
- **Display title:** resolves to `AppName • Sender`, falling back to `AppName`.
- **Deduplication:** identical payloads arriving within a 1500 ms window are dropped at the client, which prevents group-summary and update spam.

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Nothing appears on the desktop | Re-run `adb reverse tcp:5000 tcp:5000`. The tunnel drops whenever the cable is unplugged or `adb` restarts. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `adb devices` shows nothing | Try another cable or port, and make sure the USB mode isn't "charging only". |
| "Address already in use" on start | Another process holds port 5000. Find it with `ss -ltnp \| grep 5000`. |
| Notification access toggle is greyed out | Use **Allow restricted settings** (see Easy Install). |
| Notifications stop after a while | Exempt Voidroid from battery optimization in Android settings. |

## Roadmap

- [ ] Linux systemd user service + udev auto-connect hook
- [ ] Direct D-Bus integration (`org.freedesktop.Notifications`) to remove `notify-send` subshell overhead
- [ ] C implementation of the server daemon
- [ ] Screen mirroring and remote control via raw frame transport

## Contributing

Issues and pull requests are welcome. This is an early alpha, so bug reports with your Android version and desktop environment are especially useful.
