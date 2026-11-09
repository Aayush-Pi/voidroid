import socket
import os
import subprocess
import threading

HOST = "127.0.0.1"
PORT = 5000

# Global tracker for active scrcpy process
scrcpy_proc = None
proc_lock = threading.Lock()

def bring_app_to_front(pkg_name):
    """Brings the specific target app to the foreground via ADB monkey."""
    try:
        # monkey sends an intent event to launch the package's default activity
        subprocess.run(
            ["adb", "shell", "monkey", "-p", pkg_name, "-c", "android.intent.category.LAUNCHER", "1"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=2
        )
    except Exception:
        pass

def launch_or_switch_scrcpy(pkg_name, app_name):
    """Ensures only a single scrcpy window runs, switching apps if already open."""
    global scrcpy_proc

    # 1. Force wake and launch the target app on Android
    bring_app_to_front(pkg_name)

    with proc_lock:
        # Check if an existing scrcpy window is still alive
        if scrcpy_proc is not None and scrcpy_proc.poll() is None:
            # An instance is already open: bringing the app to front above is enough!
            return

        # 2. Spawn a fresh singleton scrcpy instance
        env = os.environ.copy()
        env.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
        env.setdefault("WAYLAND_DISPLAY", "wayland-0")
        env.setdefault("DISPLAY", ":0")

        cmd = [
            "scrcpy",
            f"--start-app={pkg_name}",
            "--stay-awake",
            "--turn-screen-off",
            f"--window-title=voidroid:scrcpy"
        ]

        scrcpy_proc = subprocess.Popen(cmd, env=env)
        

def dispatch_notification(pkg_name, app_name, title, body):
    icon_name = app_name.strip().lower()

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
        threading.Thread(
            target=launch_or_switch_scrcpy,
            args=(pkg_name, app_name),
            daemon=True
        ).start()

def main():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((HOST, PORT))
        s.listen()
        print("Voidroid daemon listening on port 5000...")

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

                # Spawn worker thread so the server immediately returns to s.accept()
                threading.Thread(
                    target=dispatch_notification,
                    args=(pkg_name, app_name, title, body),
                    daemon=True
                ).start()

if __name__ == "__main__":
    main()