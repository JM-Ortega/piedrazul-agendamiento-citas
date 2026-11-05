#!/usr/bin/env python3
"""Receptor sintético con la interfaz de un webhook de Discord, solo para pruebas locales.

Biblioteca estándar; escucha en :8080 dentro de la red de la prueba y no contacta nada.

  POST /api/webhooks/<id>/<token>   responde según /ctl/mode y registra la petición en
                                    /data/requests.jsonl: hora, id, sha256 de la ruta (nunca el
                                    token), código devuelto y cuerpo JSON recibido.
  GET  /metrics                     delivery_probe_state{probe="<x>"} según /ctl/probes
                                    (líneas "<x> <0|1>"): alertas de prueba controlables.

/ctl/mode (se relee en cada petición; cambiarlo reinicia el contador):
  ok          204 (como Discord sin ?wait=true)
  fail:<n>    las <n> primeras peticiones 500, después 204 (reintentos)
  404         404 {"message": "Unknown Webhook"} (webhook borrado o revocado: no se reintenta)
"""
import hashlib
import http.server
import json
import os
import re
import threading
import time

LOCK = threading.Lock()
STATE = {"mode": None, "count": 0}
PATH_RE = re.compile(r"^/api/webhooks/([0-9]+)/([A-Za-z0-9_-]+)$")


def read(path, default=""):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return default


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path != "/metrics":
            self.send_error(404)
            return
        lines = ["# TYPE delivery_probe_state gauge"]
        for line in read("/ctl/probes").splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1] in ("0", "1"):
                lines.append(f'delivery_probe_state{{probe="{parts[0]}"}} {parts[1]}')
        body = ("\n".join(lines) + "\n").encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length)
        match = PATH_RE.match(self.path)
        with LOCK:
            mode = read("/ctl/mode", "ok")
            if mode != STATE["mode"]:
                STATE["mode"], STATE["count"] = mode, 0
            STATE["count"] += 1
            if not match:
                status = 404
            elif mode.startswith("fail:") and STATE["count"] <= int(mode.split(":", 1)[1]):
                status = 500
            elif mode == "404":
                status = 404
            else:
                status = 204
            try:
                payload = json.loads(raw or b"null")
            except ValueError:
                payload = {"_invalid_json": True}
            record = {"t": time.time(), "id": match.group(1) if match else None,
                      "path_sha256": hashlib.sha256(self.path.encode()).hexdigest(),
                      "status": status, "body": payload}
            with open("/data/requests.jsonl", "a", encoding="utf-8") as f:
                f.write(json.dumps(record) + "\n")
        if status == 204:
            self.send_response(204)
            self.end_headers()
            return
        body = json.dumps({"message": "Unknown Webhook", "code": 10015} if status == 404
                          else {"message": "Internal Server Error"}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    os.makedirs("/data", exist_ok=True)
    http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
