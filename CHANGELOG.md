# Changelog

All notable changes to voidroid. The newest release is first.

## v0.6.0-beta.1

First beta. The project name is now officially lowercase: **voidroid**.

**Android app**

- **New identity:** the app display name is now `voidroid`, with a new vector launcher icon (an interlocking chain link, for device continuity). Adaptive icon resources are included (`ic_launcher.xml`, `ic_launcher_background.xml`, `ic_launcher_foreground.xml`), with monochrome/themed icon support on Android 13+.
- **New UI:** the app screen is redesigned with a dark theme (`#0F172A`), high-contrast text, and a flat, clean layout.
- **Permission status:** an inline indicator shows whether Notification Access is granted. It refreshes automatically when you come back from Android Settings (`onResume`).
- **Send test notification:** a new in-app action sends a test payload over the loopback socket, so you can check the `adb reverse tcp:5000 tcp:5000` tunnel without waiting for a real notification.
- **Battery optimization shortcut:** a direct link to Android's battery optimization settings, so you can set voidroid to Unrestricted. This keeps the listener alive on aggressive manufacturer skins such as One UI, MIUI/HyperOS, and ColorOS.
- **View on GitHub:** an outlined button opens the project repository in the browser.

**Daemon**

- **Compatibility fallback:** `server/notif.py` keeps the four-field format `<PackageName>|<AppName>|<Title>|<MessageBody>` and also accepts three-field, two-field, and plain-text messages as a fallback. Open App needs the package name, so it only works for four-field messages.

## v0.5.0-alpha

Focus: reliability, one mirror window at a time, a persistent `adb` server, and logging.

**Daemon (`server/notif.py`)**

- **Single scrcpy session:** repeated or rapid clicks on **Open App** no longer open a pile of scrcpy windows. The daemon tracks the running process (`scrcpy_proc`) behind a `threading.Lock()` and does not start a duplicate.
- **Instant app switching:** if a scrcpy window is already open, **Open App** on a different notification runs `adb shell monkey -p <PackageName> -c android.intent.category.LAUNCHER 1` to bring that app to the front, without interrupting the video stream.
- **Wayland fix:** scrcpy launched from the `systemd --user` service could lose its video and turn into an invisible, audio-only "ghost" session on Wayland/KDE Plasma. `WAYLAND_DISPLAY`, `DISPLAY`, and `XDG_RUNTIME_DIR` are now passed into every `subprocess.Popen` call.
- **Logging:** the daemon uses Python's `logging` library and writes lifecycle events, connection binds, received payloads, and socket errors to `~/.cache/voidroid/daemon.log`. scrcpy's stdout and stderr go to `~/.cache/voidroid/scrcpy.log`.

**System services (`system/`)**

- **Persistent adb server:** new `adb-server.service` (`Type=forking`) keeps `adb start-server` running on port 5037 for the whole session. This fixes the short-lived `voidroid-reverse.service` starting a temporary `adb` server and killing it on exit, which dropped active reverse tunnels.
- **Installer:** `system/install.sh` now also copies, registers, and enables `adb-server.service`. The generated udev rule uses `ENV{SYSTEMD_USER_WANTS}+=` so other user-session targets already attached to the device are preserved. If you installed an earlier version, re-run `bash system/install.sh`.

**Android app**

- **GitHub button:** a new button opens the project repository in the browser.
- **Layout:** cleaned-up centered layout with high-contrast buttons for notification access and the project page.

## v0.4.0-alpha

- **Open App button:** notifications now carry an **Open App** action (7-second timeout). Clicking it launches `scrcpy --start-app=<PackageName>` so only that app is mirrored, with the phone screen off and keyboard and mouse control from the desktop.
- **Protocol:** wire format changed to `<PackageName>|<AppName>|<Title>|<MessageBody>`. The package name tells the host which app to launch. The app and daemon must be updated together.
- **Threading:** each notification is handled in its own thread, so waiting on a click or a timeout no longer blocks incoming notifications.
- **New dependency:** `scrcpy` 3.0 or newer on the host.

## v0.3.0-alpha

- **Background service:** `system/install.sh` installs a `systemd --user` service for the listener and a udev rule that runs `adb reverse` automatically when the phone is plugged in.
- **Android app:** unchanged since `v0.2.0-alpha`.

## v0.2.0-alpha

- **Protocol:** wire format changed from `<DisplayTitle>|<MessageBody>` to `<AppName>|<Title>|<MessageBody>`, so the daemon can set the app banner and resolve icons from the system theme.
- **Chat batching:** reads `EXTRA_MESSAGING_STYLE` / `android.messages` and `EXTRA_TEXT_LINES` to forward the exact latest message from WhatsApp, Telegram, Signal and similar apps.
- **Deduplication:** now compares the message text within a 1000 ms window instead of dropping the whole payload, so rapid distinct messages are no longer swallowed.
- **Daemon:** `server/notif.py` unpacks three fields and lowercases the app name for icon lookup. The app and daemon must be updated together.

## v0.1.0-alpha

- First release: Android notification listener, ADB reverse tunnel, and `notify-send` daemon.
