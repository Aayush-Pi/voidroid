import socket
import os
import stat
import subprocess
import threading
import logging
import time
import json
from pathlib import Path

HOST = "127.0.0.1"
PORT = 5000

# Set up logging directory and file
LOG_DIR = Path.home() / ".cache" / "voidroid"
LOG_DIR.mkdir(parents=True, exist_ok=True)
LOG_FILE = LOG_DIR / "daemon.log"

logging.basicConfig(
    filename=str(LOG_FILE),
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)

# Global tracker for active scrcpy process
scrcpy_proc = None
proc_lock = threading.Lock()

GUI_ENV_KEYS = (
    "XDG_RUNTIME_DIR",
    "WAYLAND_DISPLAY",
    "DISPLAY",
    "XAUTHORITY",
    "XDG_CURRENT_DESKTOP",
    "XDG_SESSION_TYPE",
)

def _is_valid_wayland_socket(runtime_dir, name):
    """Returns True if Wayland display name resolves to a socket in runtime_dir."""
    if not name:
        return False
    sock = Path(name) if os.path.isabs(name) else Path(runtime_dir) / name
    try:
        return stat.S_ISSOCK(sock.stat().st_mode)
    except OSError:
        return False

def _find_wayland_socket(runtime_dir):
    """Finds the newest active wayland-* socket inside XDG_RUNTIME_DIR."""
    candidates = []
    try:
        for entry in Path(runtime_dir).iterdir():
            if not entry.name.startswith("wayland-") or entry.name.endswith(".lock"):
                continue
            try:
                st = entry.stat()
            except OSError:
                continue
            if stat.S_ISSOCK(st.st_mode):
                candidates.append((st.st_mtime, entry.name))
    except OSError:
        return None
    if not candidates:
        return None
    candidates.sort(reverse=True)
    return candidates[0][1]

def _find_x11_display(uid):
    """Finds the newest X11 display (:N) socket owned by uid."""
    x11_dir = Path("/tmp/.X11-unix")
    candidates = []
    try:
        for entry in x11_dir.iterdir():
            if not entry.name.startswith("X") or not entry.name[1:].isdigit():
                continue
            try:
                st = entry.stat()
            except OSError:
                continue
            if st.st_uid == uid and stat.S_ISSOCK(st.st_mode):
                candidates.append((st.st_mtime, f":{entry.name[1:]}"))
    except OSError:
        return None
    if not candidates:
        return None
    candidates.sort(reverse=True)
    return candidates[0][1]

def build_gui_env():
    """
    Builds a subprocess environment with live desktop session display variables.
    Systemd user services started at login often predate compositor env imports,
    so we refresh from `systemctl --user show-environment` and probe live sockets
    instead of falling back to hardcoded wayland-0/:0 (which triggers SDL offscreen).
    """
    uid = os.getuid()
    env = os.environ.copy()
    env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{uid}")

    try:
        res = subprocess.run(
            ["systemctl", "--user", "show-environment"],
            capture_output=True,
            text=True,
            env=env,
            timeout=2,
        )
        if res.returncode == 0:
            for line in res.stdout.splitlines():
                key, sep, val = line.partition("=")
                if sep and key in GUI_ENV_KEYS and val:
                    env[key] = val
    except Exception as e:
        logging.debug(f"Could not query systemd user environment: {e}")

    runtime_dir = env["XDG_RUNTIME_DIR"]
    if not _is_valid_wayland_socket(runtime_dir, env.get("WAYLAND_DISPLAY")):
        detected_wayland = _find_wayland_socket(runtime_dir)
        if detected_wayland:
            env["WAYLAND_DISPLAY"] = detected_wayland
        else:
            env.pop("WAYLAND_DISPLAY", None)

    if not env.get("DISPLAY"):
        detected_x11 = _find_x11_display(uid)
        if detected_x11:
            env["DISPLAY"] = detected_x11

    # Never let SDL2 silently fall back to headless 'offscreen'/'dummy' drivers
    # and leave an invisible ghost scrcpy process holding the singleton lock.
    env.setdefault("SDL_VIDEODRIVER", "wayland,x11")
    return env

def monitor_scrcpy(proc):
    """
    Blocks until scrcpy exits, waits briefly for scrcpy-server on the phone
    to release the hardware composer, then puts the OS cleanly into sleep state.
    """
    proc.wait()
    logging.info(f"scrcpy instance closed (code={proc.returncode}). Syncing phone sleep state...")

    # Give the Android scrcpy-server jar 300ms to finish restoring SurfaceFlinger
    time.sleep(0.3)

    try:
        # KEYCODE_SLEEP (223) cleanly locks the OS so the next physical button 
        # press wakes the screen on the first try instead of locking it first.
        subprocess.run(
            ["adb", "shell", "input", "keyevent", "KEYCODE_SLEEP"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2
        )
    except Exception as e:
        logging.error(f"Failed to send sleep event: {e}")

def bring_app_to_front(pkg_name):
    """Brings the specific target app to the foreground via ADB monkey."""
    try:
        subprocess.run(
            ["adb", "shell", "monkey", "-p", pkg_name, "-c", "android.intent.category.LAUNCHER", "1"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2
        )
        logging.info(f"Target app brought to front: {pkg_name}")
    except Exception as e:
        logging.error(f"Failed to bring {pkg_name} to front: {e}")

def launch_or_switch_scrcpy(pkg_name, app_name):
    """Ensures only a single scrcpy window runs, switching apps if already open."""
    global scrcpy_proc

    bring_app_to_front(pkg_name)

    with proc_lock:
        if scrcpy_proc is not None and scrcpy_proc.poll() is None:
            logging.info(f"Scrcpy already active. App switched to {pkg_name}.")
            return

        env = build_gui_env()
        if not env.get("WAYLAND_DISPLAY") and not env.get("DISPLAY"):
            logging.error("No active Wayland or X11 display found; aborting scrcpy launch.")
            return

        cmd = [
            "scrcpy",
            f"--start-app={pkg_name}",
            "--stay-awake",
            "--turn-screen-off",
            "--window-title=voidroid:scrcpy"
        ]

        try:
            with open(LOG_DIR / "scrcpy.log", "a") as scrcpy_log:
                scrcpy_proc = subprocess.Popen(cmd, env=env, stdout=scrcpy_log, stderr=scrcpy_log)
            logging.info(
                f"Spawned singleton scrcpy instance for {pkg_name} "
                f"(PID: {scrcpy_proc.pid}, WAYLAND_DISPLAY={env.get('WAYLAND_DISPLAY')}, DISPLAY={env.get('DISPLAY')})"
            )

            threading.Thread(
                target=monitor_scrcpy,
                args=(scrcpy_proc,),
                daemon=True
            ).start()
        except Exception as e:
            logging.error(f"Failed to launch scrcpy: {e}")

def dispatch_notification(pkg_name, app_name, title, body):
    app_name = app_name.strip() or "Voidroid"
    title = title.strip() or app_name
    icon_name = app_name.lower()

    try:
        res = subprocess.run([
            "notify-send",
            "-a", app_name,
            "-i", icon_name,
            "--action=open=Open App",
            "-t", "7000",
            "--",
            title,
            body
        ], stdout=subprocess.PIPE, text=True)

        if res.stdout.strip() == "open":
            logging.info(f"User clicked 'Open App' for {pkg_name}")
            threading.Thread(
                target=launch_or_switch_scrcpy,
                args=(pkg_name, app_name),
                daemon=True
            ).start()
    except Exception as e:
        logging.error(f"Failed to dispatch notify-send: {e}")

def read_socket_payload(conn):
    """Reads a UTF-8 payload terminated by NUL (\\0) or client EOF."""
    conn.settimeout(2.0)
    chunks = []
    ack_requested = False
    while True:
        chunk = conn.recv(4096)
        if not chunk:
            break
        nul_idx = chunk.find(b"\x00")
        if nul_idx != -1:
            chunks.append(chunk[:nul_idx])
            ack_requested = True
            break
        chunks.append(chunk)
    payload = b"".join(chunks).decode("utf-8", errors="replace").strip()
    return payload, ack_requested

def parse_payload(data):
    """Parses JSON payloads with fallback to legacy pipe-delimited strings."""
    if data.startswith("{"):
        try:
            obj = json.loads(data)
            if isinstance(obj, dict):
                pkg_name = str(obj.get("pkg") or "android")
                app_name = str(obj.get("app") or "Voidroid")
                title = str(obj.get("title") or app_name)
                body = str(obj.get("body") or "")
                return pkg_name, app_name, title, body
        except json.JSONDecodeError as e:
            logging.warning(f"Invalid JSON payload, falling back to legacy parser: {e}")

    parts = data.split("|", 3)
    if len(parts) == 4:
        return parts[0], parts[1], parts[2], parts[3]
    if len(parts) == 3:
        return "android", parts[0], parts[1], parts[2]
    if len(parts) == 2:
        return "android", "Voidroid", parts[0], parts[1]
    return "android", "Voidroid", "Notification", parts[0]

def handle_client(conn):
    """Reads one notification payload, ACKs framed clients, and dispatches notify-send."""
    with conn:
        try:
            data, ack_requested = read_socket_payload(conn)
        except Exception as e:
            logging.error(f"Failed reading socket payload: {e}")
            return

        if not data:
            logging.warning("Accepted connection closed with empty payload")
            return

        if ack_requested:
            try:
                conn.sendall(b"OK\n")
            except Exception as e:
                logging.warning(f"Failed sending ACK: {e}")

        pkg_name, app_name, title, body = parse_payload(data)

        logging.info(f"Incoming: [{app_name}] {title} - {body[:30]}...")
        dispatch_notification(pkg_name, app_name, title, body)

def main():
    logging.info("Starting Voidroid notification daemon...")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((HOST, PORT))
        s.listen()
        print(f"Voidroid daemon listening on port {PORT}... (logging to {LOG_FILE})")

        while True:
            conn, addr = s.accept()
            threading.Thread(
                target=handle_client,
                args=(conn,),
                daemon=True
            ).start()

if __name__ == "__main__":
    main()