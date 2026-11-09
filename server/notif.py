import socket
import os
import subprocess
import threading
import logging
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

        env = os.environ.copy()
        env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
        env.setdefault("WAYLAND_DISPLAY", "wayland-0")
        env.setdefault("DISPLAY", ":0")

        cmd = [
            "scrcpy",
            f"--start-app={pkg_name}",
            "--stay-awake",
            "--turn-screen-off",
            "--window-title=voidroid:scrcpy"
        ]

        try:
            scrcpy_log = open(LOG_DIR / "scrcpy.log", "a")
            scrcpy_proc = subprocess.Popen(cmd, env=env, stdout=scrcpy_log, stderr=scrcpy_log)
            logging.info(f"Spawned singleton scrcpy instance for {pkg_name} (PID: {scrcpy_proc.pid})")
        except Exception as e:
            logging.error(f"Failed to launch scrcpy: {e}")

def dispatch_notification(pkg_name, app_name, title, body):
    icon_name = app_name.strip().lower()

    try:
        res = subprocess.run([
            "notify-send",
            "-a", app_name,
            "-i", icon_name,
            "--action=open=Open App",
            "-t", "7000",
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

def main():
    logging.info("Starting Voidroid notification daemon...")
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((HOST, PORT))
        s.listen()
        print(f"Voidroid daemon listening on port {PORT}... (logging to {LOG_FILE})")

        while True:
            conn, addr = s.accept()
            with conn:
                data = conn.recv(4096).decode("utf-8", errors="replace").strip()
                if not data:
                    continue

                parts = data.split("|", 3)
                if len(parts) == 4:
                    pkg_name, app_name, title, body = parts
                elif len(parts) == 3:
                    pkg_name = "android"
                    app_name, title, body = parts
                elif len(parts) == 2:
                    pkg_name, app_name = "android", "Voidroid"
                    title, body = parts
                else:
                    pkg_name, app_name = "android", "Voidroid"
                    title, body = "Notification", parts[0]

                logging.info(f"Incoming: [{app_name}] {title} - {body[:30]}...")

                threading.Thread(
                    target=dispatch_notification,
                    args=(pkg_name, app_name, title, body),
                    daemon=True
                ).start()

if __name__ == "__main__":
    main()