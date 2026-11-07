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
            data = conn.recv(1024)
            message = data.decode('utf-8').strip()
            if not data:
                continue
            if "|" in message:
                title, body = message.split("|",1)
            else:
                title, body = "voidroid", message

            subprocess.run(["notify-send", title, body])

                