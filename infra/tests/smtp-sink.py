"""Servidor SMTP sintético para keycloak-lifecycle-harness.sh (HARNESS_TARGET=smtp).

Acepta AUTH PLAIN y mensajes, sin TLS ni entrega. Corre con `python -I` dentro de la red de
Keycloak. Registra en /out/sink.log líneas clave=valor: la credencial solo como SHA-256 de
"usuario\\0contraseña", y del mensaje solo destinatario y remitente, nunca el cuerpo (trae el
enlace de recuperación).
"""

import base64
import hashlib
import socket
import threading

LOG = open("/out/sink.log", "a", buffering=1)


def session(conn):
    stream = conn.makefile("rwb", buffering=0)

    def reply(text):
        stream.write(text.encode() + b"\r\n")

    reply("220 harness ESMTP")
    in_data = False
    rcpt = ""
    sender = ""
    for raw in stream:
        line = raw.decode("utf-8", "replace").rstrip("\r\n")
        upper = line.upper()
        if in_data:
            if line.startswith("From:"):
                sender = line[len("From:"):].strip()
            if line == ".":
                in_data = False
                LOG.write(f"mail rcpt={rcpt} from={sender}\n")
                reply("250 aceptado")
        elif upper.startswith("EHLO"):
            stream.write(b"250-harness\r\n250 AUTH PLAIN\r\n")
        elif upper.startswith("AUTH PLAIN"):
            _, user, password = base64.b64decode(line.split()[-1]).split(b"\0")
            LOG.write(f"auth={hashlib.sha256(user + bytes(1) + password).hexdigest()}\n")
            reply("235 autenticado")
        elif upper.startswith("RCPT TO:"):
            rcpt = line.split(":", 1)[1].strip(" <>")
            reply("250 ok")
        elif upper == "DATA":
            in_data = True
            reply("354 adelante")
        elif upper == "QUIT":
            reply("221 fin")
            break
        else:
            reply("250 ok")
    conn.close()


server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("0.0.0.0", 2525))
server.listen(16)
while True:
    connection, _ = server.accept()
    threading.Thread(target=session, args=(connection,), daemon=True).start()
