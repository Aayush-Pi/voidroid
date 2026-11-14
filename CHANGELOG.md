# Changelog

All notable changes to voidroid. The newest release is first.

## v0.6.2-beta.3

Focus: reliable delivery over USB, correct handling of stacked chat notifications, a new app ID, and a debug log viewer.

**Breaking: new app ID**

- `applicationId` changed from `com.example.voidroid` to `io.github.aayushpi.voidroid`. The `namespace` is still `com.example.voidroid`, so the source folders did not move.
- Android treats this as a different app. If you are on `v0.6.1-beta.2` or earlier, uninstall the old app first (`adb uninstall com.example.voidroid`), otherwise you end up with two copies. Grant Notification Access to the new app again.
- `versionCode` is now `4` and `versionName` is `0.6.2-beta.3`. The release APK is signed with a dedicated release keystore (`voidroid-release.jks`, loaded from the gitignored `android/keystore.properties`). Release asset: `voidroid-v0.6.2-beta.3.apk`.

**Wire protocol and delivery (`NotificationService.kt`, `MainActivity.kt`, `server/notif.py`)**

- **JSON framing:** notifications are now one UTF-8 JSON object terminated by a NUL byte: `{"pkg": "<PackageName>", "app": "<AppName>", "title": "<Title>", "body": "<MessageBody>"}\0`. This replaces the pipe-delimited string as the primary format.
- **Acknowledgement and retries (`sendPayloadWithAck`):** after sending to `127.0.0.1:5000`, the app waits for `OK\n` from `notif.py` and retries up to 3 times (150 ms, then 300 ms backoff) on a socket error or a missing acknowledgement. This fixes dropped notifications caused by Android closing the socket before `adbd` finished the `OPEN`/`OKAY` handshake of the reverse tunnel.
- **Special characters:** titles and bodies keep `|`, quotes, newlines, and Unicode exactly as they were instead of having `|` stripped.
- **Daemon:** `parse_payload` tries NUL-terminated JSON first and falls back to the legacy pipe-delimited formats (4, 3, 2, or 1 fields). Each accepted connection is handled in its own daemon thread (`handle_client`), and empty socket closes are logged. `notify-send` is called with `--` before the title and body, so text starting with `-` is never parsed as a flag.

**Stacked and grouped notifications (`NotificationService.kt`)**

- **Per-notification tracking:** the global `lastSentMessageText` check is replaced by tracking per `sbn.key` (`sentByKey`, cleared in `onNotificationRemoved`). Unread conversations that WhatsApp or Android re-post no longer re-spam, and different chats sending identical text are no longer suppressed.
- **Latest message by timestamp:** `extractLatestMessage` scans every `Notification.EXTRA_MESSAGES` (`MessagingStyle`) bundle and picks the one with the highest `time` (the later index wins a tie) instead of assuming array order. If the latest bundle is a media attachment with blank text, it falls back to `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, and then `"Attachment"`.
- **Group summary fallback:** a `FLAG_GROUP_SUMMARY` notification carrying structured `EXTRA_MESSAGES` or `EXTRA_TEXT_LINES` is sent after a 250 ms delay if no child notification from that group fires within 500 ms.

**In-app debug log viewer (`NotificationLogStore.kt`, `MainActivity.kt`, `NotificationService.kt`)**

- New `NotificationLogStore.kt`: a thread-safe, in-memory circular buffer of the last 50 events (`ArrayDeque`, nothing written to disk). It records listener connect and disconnect, drop and duplicate reasons, `SENT (try=N)`, and `SOCK_ERR`.
- New **View RAM logs** button in `MainActivity` opens a dialog, newest first, with **Refresh**, **Copy**, and **Clear**, so you can debug on the phone without `adb logcat`.

**Docs**

- README restructured: quick install first, details in collapsible sections, a reference section for the protocol and project structure, and a "Help test the beta" section.
- New install guidance for phones where Play Protect's Enhanced Fraud Protection blocks sideloading: install over USB with `adb`, or pause Play Protect briefly.

## v0.6.1-beta.2

Focus: the phone's lock state after you close the mirror window.

**Daemon (`server/notif.py`)**

- **Problem:** closing the desktop scrcpy window left Android's display in an inconsistent state. scrcpy runs with `--turn-screen-off`, so the display was blank while Android stayed awake internally. Picking up the phone took two presses of the power button: one for Android to realize it should sleep and lock, and a second to wake the lock screen.
- **Fix:** a watcher thread (`monitor_scrcpy`) now starts with every scrcpy launch. When the scrcpy process ends (for example when you close the window), it waits 300 ms for `scrcpy-server` to release the display, then runs `adb shell input keyevent KEYCODE_SLEEP`. The phone locks immediately, and the first power-button press or fingerprint touch wakes it.
- **Non-blocking:** the watcher runs in its own daemon thread, so exit cleanup never stalls the socket listener.
- **Flags:** removed `--power-off-on-close` from the scrcpy arguments. It raced with `adb` teardown and kept `scrcpy-server` from restoring the display cleanly.

**Android app**

- `versionCode` and `versionName` are now `0.6.1-beta.2` in `android/app/build.gradle`. The notification listener logic is unchanged, and the app was rebuilt for version parity and asset tagging. Release asset: `voidroid-v0.6.1-beta.2.apk`.

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
