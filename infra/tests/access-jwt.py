#!/usr/bin/env python3
"""Emisor sintético de JWT con la forma de Cloudflare Access, solo para pruebas locales.

Usa la biblioteca estándar y el binario openssl (firma RS256); no depende de paquetes de
terceros ni contacta ningún servicio. Las claves son de prueba y se crean por ejecución.

Uso:
  access-jwt.py keygen <dir> <kid>        → <dir>/<kid>.key (privada) y añade la pública a
                                            <dir>/jwks.json (formato /cdn-cgi/access/certs)
  access-jwt.py sign <dir> <kid> <claims-json> [alg]
                                          → imprime el token. claims-json admite las claves
                                            especiales "_exp_in" y "_iat_in" (segundos
                                            relativos a ahora). alg: RS256 (por defecto),
                                            none (sin firma) o HS256 (firmado con el JWKS
                                            público como secreto: confusión de algoritmo).
"""
import base64
import json
import os
import subprocess
import sys
import time


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def keygen(directory: str, kid: str) -> None:
    os.makedirs(directory, exist_ok=True)
    key = os.path.join(directory, f"{kid}.key")
    subprocess.run(["openssl", "genrsa", "-out", key, "2048"], check=True, capture_output=True)
    os.chmod(key, 0o600)
    text = subprocess.run(["openssl", "rsa", "-in", key, "-noout", "-modulus"], check=True,
                          capture_output=True, text=True).stdout
    modulus = bytes.fromhex(text.strip().split("=", 1)[1])
    jwk = {"kid": kid, "kty": "RSA", "alg": "RS256", "use": "sig",
           "e": b64u((65537).to_bytes(3, "big")), "n": b64u(modulus)}
    path = os.path.join(directory, "jwks.json")
    doc = {"keys": []}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    doc["keys"] = [k for k in doc["keys"] if k["kid"] != kid] + [jwk]
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f)


def sign(directory: str, kid: str, claims_json: str, alg: str = "RS256") -> str:
    claims = json.loads(claims_json)
    now = int(time.time())
    for special, target in (("_exp_in", "exp"), ("_iat_in", "iat"), ("_nbf_in", "nbf")):
        if special in claims:
            claims[target] = now + int(claims.pop(special))
    header = {"alg": alg, "kid": kid, "typ": "JWT"}
    signing_input = f"{b64u(json.dumps(header).encode())}.{b64u(json.dumps(claims).encode())}"
    if alg == "none":
        return signing_input + "."
    if alg == "HS256":
        import hashlib
        import hmac
        with open(os.path.join(directory, "jwks.json"), "rb") as f:
            secret = f.read()
        mac = hmac.new(secret, signing_input.encode(), hashlib.sha256).digest()
        return f"{signing_input}.{b64u(mac)}"
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", os.path.join(directory, f"{kid}.key")],
                         input=signing_input.encode(), check=True, capture_output=True).stdout
    return f"{signing_input}.{b64u(sig)}"


if __name__ == "__main__":
    if len(sys.argv) >= 4 and sys.argv[1] == "keygen":
        keygen(sys.argv[2], sys.argv[3])
    elif len(sys.argv) >= 5 and sys.argv[1] == "sign":
        print(sign(*sys.argv[2:6]))
    else:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
