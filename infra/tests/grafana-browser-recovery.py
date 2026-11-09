#!/usr/bin/env python3
"""Navegador real (Playwright: Chromium y Firefox) contra Grafana, solo para pruebas locales.

Corre dentro de un contenedor unido a la red del stack de prueba. Lo llama
infra/tests/grafana-access-check.sh; imprime una línea JSON por comprobación y nunca la
contraseña ni el JWT.

Túnel: un reenviador TCP escucha en 127.0.0.1:3000 del contenedor y lleva a grafana:3000, que es
lo que ve un navegador con `ssh -L 3000:127.0.0.1:3000`: origen http://localhost:3000. El
transporte SSH no se prueba aquí (es estándar); sí todo lo que depende del navegador: redirección
a /login pese a una root_url https pública, formulario, cookie de sesión Secure sobre http,
navegación, recarga y logout.

Ruta pública (solo Chromium): https://<host público> resuelto (/etc/hosts) al Traefik de prueba, con el
certificado de cliente de origen y la cabecera Cf-Access-Jwt-Assertion que pondría Access.

Entorno: GRAFANA_UPSTREAM (grafana:3000), ADMIN_PASSWORD, PUBLIC_HOST, VIEWER_JWT,
CLIENT_CERT, CLIENT_KEY.
"""
import json
import os
import socket
import threading

from playwright.sync_api import sync_playwright

RESULTS = []


def record(name, ok, detail=""):
    RESULTS.append({"name": name, "ok": bool(ok), "detail": detail})
    print(json.dumps(RESULTS[-1]), flush=True)


def forward(listen_port, upstream):
    host, port = upstream.rsplit(":", 1)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", listen_port))
    srv.listen(64)

    def pipe(a, b):
        try:
            while True:
                data = a.recv(65536)
                if not data:
                    break
                b.sendall(data)
        except OSError:
            pass
        finally:
            for s in (a, b):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

    def loop():
        while True:
            client, _ = srv.accept()
            up = socket.create_connection((host, int(port)))
            threading.Thread(target=pipe, args=(client, up), daemon=True).start()
            threading.Thread(target=pipe, args=(up, client), daemon=True).start()

    threading.Thread(target=loop, daemon=True).start()


def fetch_json(page, path):
    return page.evaluate(
        """async (p) => { const r = await fetch(p, {credentials: 'same-origin'});
                         let b = null; try { b = await r.json(); } catch (e) {}
                         return {status: r.status, body: b}; }""", path)


def tunnel(browser_type, origin):
    tag = f"{browser_type.name} {origin}"
    browser = browser_type.launch()
    ctx = browser.new_context()
    page = ctx.new_page()
    try:
        page.goto(origin + "/", wait_until="domcontentloaded")
        page.wait_for_url("**/login**", timeout=30000)
        record(f"túnel {tag}: sin sesión redirige a {origin}/login (no a la root_url pública)",
               page.url.startswith(origin + "/login"), page.url)
        page.locator('input[name="user"]').fill("admin")
        page.locator('input[name="password"]').fill(os.environ["ADMIN_PASSWORD"])
        page.locator('button[type="submit"]').click()
        page.wait_for_url(lambda u: "/login" not in u, timeout=30000)
        record(f"túnel {tag}: el formulario de login entra y queda en {origin}",
               page.url.startswith(origin + "/"), page.url)
        cookies = {c["name"]: c for c in ctx.cookies()}
        sess = cookies.get("grafana_session")
        record(f"túnel {tag}: el navegador guarda grafana_session (Secure, HttpOnly, SameSite=Strict) sobre http",
               sess is not None and sess["secure"] and sess["httpOnly"] and sess.get("sameSite") == "Strict",
               json.dumps({k: sess[k] for k in ("domain", "secure", "httpOnly", "sameSite")}) if sess else "sin cookie")
        user = fetch_json(page, "/api/user")
        record(f"túnel {tag}: la sesión es del administrador local",
               user["status"] == 200 and user["body"]["login"] == "admin" and user["body"]["isGrafanaAdmin"],
               str(user["status"]))
        settings = fetch_json(page, "/api/admin/settings")
        record(f"túnel {tag}: acción de administración del servidor con la sesión (200)",
               settings["status"] == 200, str(settings["status"]))
        page.goto(origin + "/d/piedrazul-plataforma", wait_until="domcontentloaded")
        page.wait_for_timeout(1500)
        record(f"túnel {tag}: navega a un dashboard sin volver a /login",
               "/d/piedrazul-plataforma" in page.url, page.url)
        page.reload(wait_until="domcontentloaded")
        page.wait_for_timeout(1000)
        again = fetch_json(page, "/api/user")
        record(f"túnel {tag}: la sesión sobrevive a una recarga",
               again["status"] == 200 and again["body"]["login"] == "admin", str(again["status"]))
        page.goto(origin + "/logout", wait_until="domcontentloaded")
        page.wait_for_url("**/login**", timeout=30000)
        after = fetch_json(page, "/api/user")
        record(f"túnel {tag}: logout cierra la sesión", after["status"] == 401, str(after["status"]))
    except Exception as exc:  # noqa: BLE001 - se informa como falla
        record(f"túnel {tag}: escenario completo", False, f"{type(exc).__name__}: {exc}"[:300])
    finally:
        browser.close()


def public(pw):
    host = os.environ["PUBLIC_HOST"]
    origin = f"https://{host}"
    # El contenedor resuelve el nombre público al Traefik de prueba (/etc/hosts)
    browser = pw.chromium.launch()
    certs = [{"origin": origin, "certPath": os.environ["CLIENT_CERT"], "keyPath": os.environ["CLIENT_KEY"]}]
    try:
        ctx = browser.new_context(ignore_https_errors=True, client_certificates=certs,
                                  extra_http_headers={"Cf-Access-Jwt-Assertion": os.environ["VIEWER_JWT"]})
        page = ctx.new_page()
        page.goto(origin + "/d/piedrazul-plataforma", wait_until="domcontentloaded")
        page.wait_for_timeout(2000)
        user = fetch_json(page, "/api/user")
        record("pública chromium: con JWT de Access el dashboard carga como Viewer, sin login",
               "/login" not in page.url and user["status"] == 200 and user["body"]["login"] != "admin"
               and not user["body"]["isGrafanaAdmin"], f"{page.url} {user['status']}")
        record("pública chromium: Grafana no deja cookie de sesión en el navegador",
               "grafana_session" not in {c["name"] for c in ctx.cookies()}, "")
        ctx.close()
        anon = browser.new_context(ignore_https_errors=True, client_certificates=certs)
        page = anon.new_page()
        resp = page.goto(origin + "/", wait_until="domcontentloaded")
        record("pública chromium: sin JWT termina en /login, que no se enruta (404): sin login local",
               "/login" in page.url and resp is not None and resp.status == 404, f"{page.url} {resp.status if resp else '-'}")
    except Exception as exc:  # noqa: BLE001
        record("pública chromium: escenario completo", False, f"{type(exc).__name__}: {exc}"[:300])
    finally:
        browser.close()


if __name__ == "__main__":
    forward(3000, os.environ.get("GRAFANA_UPSTREAM", "grafana:3000"))
    with sync_playwright() as pw:
        for bt in (pw.chromium, pw.firefox):
            for origin in ("http://localhost:3000", "http://127.0.0.1:3000"):
                tunnel(bt, origin)
        if os.environ.get("PUBLIC_HOST"):
            public(pw)
    raise SystemExit(0 if all(r["ok"] for r in RESULTS) else 1)
