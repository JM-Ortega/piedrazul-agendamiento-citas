"""Login de navegador contra Keycloak (authorization code + PKCE) para el harness.

Recorre las páginas reales del flujo browser de un realm —formulario de usuario y
contraseña, acciones requeridas (UPDATE_PASSWORD, CONFIGURE_TOTP) y formulario OTP— como lo
haría un navegador, sin JavaScript. Solo biblioteca estándar: corre con `python -I` en una
imagen python:*-slim dentro de la red de Keycloak.

Uso: python -I keycloak-browser-login.py <modo>
  first-login  contraseña temporal → acciones requeridas → código. Cambia la contraseña por
               KBL_NEW_PASSWORD (antes intenta KBL_SHORT_PASSWORD si está definida) y enrola
               TOTP; guarda la semilla en KBL_OTP_FILE (0600)
  login        contraseña → ¿desafío OTP? → OTP incorrecto (debe rechazarse) → OTP correcto
               (KBL_OTP_FILE) → código
  password     solo contraseña: informa si Keycloak entrega un código sin segundo factor
  direct-grant password grant del cliente admin-cli: sin OTP y, si hay KBL_OTP_FILE, con OTP

Entorno: KBL_BASE (p. ej. http://keycloak:8180), KBL_REALM, KBL_CLIENT, KBL_REDIRECT (por
defecto, la consola de administración según el issuer del realm), KBL_USER, KBL_PASSWORD,
KBL_NEW_PASSWORD, KBL_OTP_FILE.
Salida: líneas clave=valor, nunca contraseñas, semillas ni códigos. Código 0 si el modo
llegó a un resultado observable (que la salida describe); 1 si el flujo no fue reconocible.
"""

import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ENV = os.environ
BASE = ENV.get("KBL_BASE", "").rstrip("/")
REALM = ENV.get("KBL_REALM", "master")
CLIENT = ENV.get("KBL_CLIENT", "security-admin-console")
# Sin KBL_REDIRECT: el de la consola de administración según el issuer que publica el realm
# (Keycloak valida el redirect_uri contra su hostname configurado, no contra KBL_BASE)
REDIRECT = ENV.get("KBL_REDIRECT", "")


def out(key, value):
    print(f"{key}={value}", flush=True)


class Unrecognized(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(NoRedirect)
COOKIES = {}


def to_base(url):
    """Keycloak arma URLs con KC_HOSTNAME: se reescriben al servidor local."""
    p = urllib.parse.urlsplit(url)
    b = urllib.parse.urlsplit(BASE)
    return urllib.parse.urlunsplit((b.scheme, b.netloc, p.path, p.query, p.fragment))


def request(url, data=None):
    """Una petición, sin seguir redirecciones. Cookies propias: las de Keycloak son Secure
    y el harness habla HTTP con el contenedor."""
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(to_base(url), data=body)
    if COOKIES:
        req.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in COOKIES.items()))
    try:
        resp = OPENER.open(req, timeout=30)
        status, headers, text = resp.status, resp.headers, resp.read().decode()
    except urllib.error.HTTPError as e:
        status, headers, text = e.code, e.headers, e.read().decode()
    for c in headers.get_all("Set-Cookie") or []:
        name, _, rest = c.partition("=")
        value = rest.split(";", 1)[0]
        if "Max-Age=0" in c or "Expires=Thu, 01 Jan 1970" in c:
            COOKIES.pop(name, None)
        else:
            COOKIES[name] = value
    return status, headers, text


def follow(url, data=None):
    """Sigue redirecciones hasta una página o hasta el redirect_uri (devuelve el código)."""
    status, headers, text = request(url, data)
    for _ in range(10):
        if status not in (301, 302, 303, 307):
            return None, status, text
        loc = urllib.parse.urljoin(url, headers["Location"])
        if loc.startswith(REDIRECT) or urllib.parse.urlsplit(loc).path == urllib.parse.urlsplit(REDIRECT).path:
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query or urllib.parse.urlsplit(loc).fragment)
            if "code" in q:
                return q["code"][0], status, ""
            return None, status, "error=" + q.get("error", ["?"])[0]
        url = loc
        status, headers, text = request(url)
    raise Unrecognized("too_many_redirects")


def form(page, form_id=None):
    """(action, campos ocultos) del formulario `form_id`, o del primero."""
    forms = re.findall(r"<form\b([^>]*)>(.*?)</form>", page, re.S | re.I)
    for attrs, inner in forms:
        if form_id and not re.search(r'id="%s"' % re.escape(form_id), attrs):
            continue
        action = re.search(r'action="([^"]+)"', attrs)
        if not action:
            continue
        hidden = {}
        for inp in re.findall(r"<input\b[^>]*>", inner, re.I):
            if re.search(r'type="hidden"', inp):
                n = re.search(r'name="([^"]+)"', inp)
                v = re.search(r'value="([^"]*)"', inp)
                if n:
                    hidden[n.group(1)] = html.unescape(v.group(1)) if v else ""
        return html.unescape(action.group(1)), hidden
    return None, {}


def page_kind(page):
    if 'name="totpSecret"' in page:
        return "configure_totp"
    if 'name="password-new"' in page:
        return "update_password"
    if 'name="otp"' in page:
        return "otp"
    if 'name="password"' in page and 'name="username"' in page:
        return "login"
    return "other"


def page_error(page):
    m = re.search(r'id="input-error[^"]*"[^>]*>\s*(.*?)\s*<', page, re.S) or \
        re.search(r'class="[^"]*(?:pf-m-danger|alert-error|kc-feedback-text)[^"]*"[^>]*>(?:\s*<[^>]+>)*\s*([^<]+)', page, re.S)
    return re.sub(r"\s+", "_", html.unescape(m.group(1)).strip().lower())[:60] if m else "none"


def totp(secret_b32, offset=0):
    key = base64.b32decode(secret_b32.upper() + "=" * (-len(secret_b32) % 8))
    counter = int(time.time()) // 30 + offset
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    o = digest[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", digest[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def fresh_totp(secret_b32):
    """Código de un paso de tiempo todavía no usado: Keycloak rechaza reusar un código
    (otpPolicyCodeReusable=false). El último paso usado se guarda junto a la semilla."""
    marker = ENV["KBL_OTP_FILE"] + ".step"
    last = int(open(marker).read()) if os.path.exists(marker) else -1
    while int(time.time()) // 30 <= last:
        time.sleep(1)
    with open(marker, "w") as f:
        f.write(str(int(time.time()) // 30))
    return totp(secret_b32)


def wrong_otp(secret_b32):
    valid = {totp(secret_b32, d) for d in (-1, 0, 1)}
    while True:
        c = "%06d" % secrets.randbelow(1000000)
        if c not in valid:
            return c


def discover_redirect():
    with urllib.request.urlopen(f"{BASE}/realms/{REALM}/.well-known/openid-configuration", timeout=30) as r:
        issuer = json.loads(r.read())["issuer"]
    return issuer.rsplit("/realms/", 1)[0] + f"/admin/{REALM}/console/"


def start():
    global REDIRECT
    REDIRECT = REDIRECT or discover_redirect()
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    url = f"{BASE}/realms/{REALM}/protocol/openid-connect/auth?" + urllib.parse.urlencode({
        "client_id": CLIENT, "redirect_uri": REDIRECT, "response_type": "code", "scope": "openid",
        "state": secrets.token_hex(8), "nonce": secrets.token_hex(8),
        "code_challenge": challenge, "code_challenge_method": "S256"})
    code, status, page = follow(url)
    if page_kind(page) != "login":
        raise Unrecognized(f"no_login_page:{status}")
    return verifier, page


def exchange(code, verifier):
    status, _, text = request(f"{BASE}/realms/{REALM}/protocol/openid-connect/token", {
        "grant_type": "authorization_code", "client_id": CLIENT, "code": code,
        "redirect_uri": REDIRECT, "code_verifier": verifier})
    if status != 200:
        return "rejected"
    # El access token de la consola de administración es liviano: la identidad va en el id_token
    claims = json.loads(base64.urlsafe_b64decode(json.loads(text)["id_token"].split(".")[1] + "=="))
    return "ok" if claims.get("preferred_username") == ENV["KBL_USER"].lower() else "other_user"


def submit_password(page, password):
    action, fields = form(page, "kc-form-login")
    fields.update({"username": ENV["KBL_USER"], "password": password, "credentialId": ""})
    return follow(action, fields)


def finish(code, verifier):
    out("browser.code", "issued" if code else "none")
    if code:
        out("browser.token", exchange(code, verifier))


def first_login():
    verifier, page = start()
    code, _, page = submit_password(page, ENV["KBL_PASSWORD"])
    actions = []
    while not code:
        kind = page_kind(page)
        if kind == "update_password":
            short = ENV.get("KBL_SHORT_PASSWORD", "")
            if short and "UPDATE_PASSWORD_SHORT" not in actions:
                # Primero una contraseña que no cumple la política: debe volver al mismo formulario
                action, fields = form(page)
                fields.update({"password-new": short, "password-confirm": short})
                actions.append("UPDATE_PASSWORD_SHORT")
                code, _, page = follow(action, fields)
                out("first_login.short_password", "rejected" if not code and page_kind(page) == "update_password" else "accepted")
                if not code and page_kind(page) == "update_password":
                    out("first_login.short_password_error", "min_length_16" if "minimum length 16" in page else page_error(page))
                continue
            action, fields = form(page)
            fields.update({"password-new": ENV["KBL_NEW_PASSWORD"], "password-confirm": ENV["KBL_NEW_PASSWORD"]})
            actions.append("UPDATE_PASSWORD")
            code, _, page = follow(action, fields)
        elif kind == "configure_totp":
            action, fields = form(page)
            seed = fields["totpSecret"]
            # La semilla que muestra la página en Base32 (la que el autenticador escanea)
            enc = re.search(r'id="kc-totp-secret-key"[^>]*>\s*([A-Z2-7 ]+)\s*<', page)
            seed_b32 = enc.group(1).replace(" ", "") if enc else base64.b32encode(seed.encode()).decode().rstrip("=")
            with open(os.open(ENV["KBL_OTP_FILE"], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
                f.write(seed_b32)
            fields.update({"totp": fresh_totp(seed_b32), "userLabel": "harness", "mode": fields.get("mode", "")})
            actions.append("CONFIGURE_TOTP")
            code, _, page = follow(action, fields)
        else:
            out("first_login.stuck", f"{kind}:{page_error(page)}")
            break
    out("first_login.actions", ",".join(a for a in actions if a != "UPDATE_PASSWORD_SHORT") or "none")
    finish(code, verifier)


def login():
    verifier, page = start()
    code, _, page = submit_password(page, ENV["KBL_PASSWORD"])
    if code:
        out("browser.otp_challenge", "no")
        return finish(code, verifier)
    kind = page_kind(page)
    out("browser.otp_challenge", "yes" if kind == "otp" else f"no:{kind}:{page_error(page)}")
    if kind != "otp":
        return finish(None, verifier)
    with open(ENV["KBL_OTP_FILE"]) as f:
        seed = f.read().strip()
    action, fields = form(page, "kc-otp-login-form")
    code, _, page = follow(action, dict(fields, otp=wrong_otp(seed)))
    out("browser.wrong_otp", "issued_code" if code else ("rejected" if page_kind(page) == "otp" else f"other:{page_kind(page)}"))
    if code:
        return finish(code, verifier)
    action, fields = form(page, "kc-otp-login-form")
    code, _, page = follow(action, dict(fields, otp=fresh_totp(seed)))
    finish(code, verifier)


def password_only():
    verifier, page = start()
    code, _, page = submit_password(page, ENV["KBL_PASSWORD"])
    out("browser.password_only", "code_issued" if code else page_kind(page))
    finish(code, verifier)


def direct_grant():
    def grant(extra):
        status, _, text = request(f"{BASE}/realms/{REALM}/protocol/openid-connect/token", dict({
            "grant_type": "password", "client_id": "admin-cli", "username": ENV["KBL_USER"],
            "password": ENV["KBL_PASSWORD"]}, **extra))
        if status == 200:
            return "token"
        body = json.loads(text) if text.startswith("{") else {}
        return re.sub(r"\s+", "_", (body.get("error_description") or body.get("error") or str(status)).lower())

    out("direct_grant.without_otp", grant({}))
    otp_file = ENV.get("KBL_OTP_FILE", "")
    if otp_file and os.path.exists(otp_file):
        with open(otp_file) as f:
            seed = f.read().strip()
        out("direct_grant.wrong_otp", grant({"totp": wrong_otp(seed)}))
        out("direct_grant.with_otp", grant({"totp": fresh_totp(seed)}))


MODES = {"first-login": first_login, "login": login, "password": password_only, "direct-grant": direct_grant}

if __name__ == "__main__":
    try:
        MODES[sys.argv[1]]()
    except (Unrecognized, KeyError, IndexError) as e:
        out("error", type(e).__name__ + ":" + str(e)[:80])
        sys.exit(1)
