import socket
import subprocess

HOST = "127.0.0.1"
PORT = 5000

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen()
    print("Waiting for the phone to connect...")

    while True:
        conn, addr = s.accept()

        with conn:
            data = conn.recv(1024).decode('utf-8').strip()
            message = data.split("|", 2)
            if not data:
                continue
            if len(message) == 3:
                appname, title, body = message
            elif len(message) == 2:
                appname = "voidroid"
                title, body = message
            else:
                appname = "voidroid"
                title = "Notifictaion"
                body = message              

            subprocess.run(["notify-send", "-a", appname , "-i" , appname, title , body])

                