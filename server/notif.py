import socket
import subprocess
import threading

HOST = "127.0.0.1"
PORT = 5000

def launch_scrcpy(pkg_name, app_name):
    """Spawns an ephemeral, focused scrcpy instance."""
    cmd = [
        "scrcpy",
        "--start-app={pkg_name}",
        "--stay-awake",
        "--turn-screen-off",
        "--window-title=Voidroid"
    ]
    subprocess.Popen(cmd)

def dispatch_notification(pkg_name, app_name, title, body):
    """Fires notify-send and listens for button click."""
    icon_name = app_name.strip().lower()
    
    # notify-send blocks until clicked or expired (-t 7000 = 7s)
    res = subprocess.run([
        "notify-send",
        "-a", app_name,
        "-i", icon_name,
        "--action=open=Open App",
        "-t", "7000",
        title,
        body
    ], stdout=subprocess.PIPE, text=True)

    # If the user clicked the action button, res.stdout contains 'open'
    if res.stdout.strip() == "open":
        threading.Thread(target=launch_scrcpy, args=(pkg_name, app_name), daemon=True).start()

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