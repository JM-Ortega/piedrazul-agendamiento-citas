# Acceso de operadores a Grafana

## Propósito

Usar Grafana a diario por `https://grafana.piedrazul.org` detrás de Cloudflare Access, con Grafana
validando el JWT de Access y autorizando por su propia lista, y conservar una recuperación
administrativa independiente de Cloudflare por túnel SSH y el administrador local.

**Estado.** El repositorio implementa y prueba en local la configuración (Grafana, Traefik, rol de
Ansible). Fuera del repositorio, el operador **preparó** (informado el 2026-11-04; no integrado ni
verificado contra este stack):

- aplicación de Access `grafana` para `grafana.piedrazul.org`, con la política
  `Grafana Operators` (dos identidades individuales) y sesión de 24 h;
- equipo de Zero Trust `piedrazul.cloudflareaccess.com` y el AUD Tag de la aplicación, que el
  operador guarda (no está en el repositorio).

**No existen todavía**: el registro DNS `grafana`, el router en el inventario
(`grafana_public_domain: ""`), un despliegue con `obs_grafana_access_enabled` ni ninguna
verificación en vivo. Hasta entonces Grafana se usa solo por túnel (modo privado, como S1/S2).
Lo anterior es un registro de lo preparado, no una garantía del repositorio: antes de activar,
confirmarlo en el panel de Cloudflare (paso 1 de [Activación](#activación)).

## Modelo

```
navegador ──HTTPS──▶ Cloudflare (Access: política de identidad; agrega Cf-Access-Jwt-Assertion)
                         │ HTTPS + certificado de cliente de Authenticated Origin Pulls
                         ▼
                 Traefik :443 (router grafana, solo con grafana_public_domain)
                   · TLS cloudflare-aop: sin el certificado de Cloudflare, no hay handshake
                   · sin /login (contraseña local) → 404
                   · quita Authorization (Basic, tokens) y Cookie (sesiones locales)
                         │ red interna piedrazul_obs_edge (solo traefik y grafana)
                         ▼
                 Grafana :3000 ── valida el JWT en cada petición (sin sesión propia)
                         ▲
operador ──ssh -L 3000:127.0.0.1:3000──▶ loopback del host: login local (recuperación)
```

Capas, de afuera hacia adentro; ninguna reemplaza a otra:

| Capa | Qué garantiza | Qué no garantiza |
|---|---|---|
| Access (política) | solo identidades permitidas reciben un JWT de la aplicación | nada si el origen se alcanza sin pasar por Access |
| Authenticated Origin Pulls | solo Cloudflare completa TLS con el origen | el certificado de AOP es compartido por todas las zonas de Cloudflare: no prueba que la petición pasó por **esta** aplicación de Access |
| Traefik | ninguna credencial distinta del JWT llega por la ruta pública | no autentica |
| Grafana (JWT) | firma del equipo, `iss`, `aud` de esta aplicación, `type=app`, `exp` presente y vigente, correo en la lista → rol | la revocación de una sesión de Access antes de su `exp` (ver [Limitaciones](#limitaciones)) |

Por eso la validación del JWT en Grafana es la barrera que no depende de la red: un JWT de otra
aplicación o de otro equipo de Access no sirve.

## Qué valida Grafana

Configuración en `infra/observability/compose.yml` (variables `GF_AUTH_JWT_*`):

| Comprobación | Valor | Fallo |
|---|---|---|
| Cabecera | `Cf-Access-Jwt-Assertion` (sin ella no hay identidad por JWT) | 401 |
| Firma | RS256 con una clave del JWKS `https://<equipo>.cloudflareaccess.com/cdn-cgi/access/certs`; `alg=none`, HS256 o una clave fuera del JWKS se rechazan | 401 |
| `iss` | `https://<equipo>.cloudflareaccess.com` | 401 |
| `aud` | el AUD Tag de la aplicación de Access de Grafana | 401 |
| `type` | `app` (un token de organización no sirve) | 401 |
| `exp` | vigente; un token **sin** `exp` no obtiene rol (Grafana no lo exige por sí mismo) | 401 / 403 |
| Autorización | `lower(email)` en `GRAFANA_ACCESS_EDITORS` → Editor; en `GRAFANA_ACCESS_VIEWERS` → Viewer; otro, con comas o sin correo → sin rol (`role_attribute_strict`) | 403 |

Nunca se asigna `Admin` de organización ni administrador del servidor por JWT
(`allow_assign_grafana_admin=false`, la expresión solo produce Editor o Viewer). El primer acceso
crea el usuario (externo, `auto_sign_up`); su rol se recalcula en cada petición.

El JWKS se guarda en la caché de Grafana (en su base, sobrevive a reinicios) durante
`GF_AUTH_JWT_CACHE_TTL=5m`.

Snapshots y dashboards compartidos públicamente están deshabilitados (`GF_SNAPSHOTS_ENABLED`,
`GF_PUBLIC_DASHBOARDS_ENABLED`): se ven con solo el enlace, sin pasar por la lista.

## Separación de administración

| Autoridad | Quién | Cómo | Alcance |
|---|---|---|---|
| Plataforma | operador con SSH (`ansible` + sudo) | túnel, `docker compose`, Ansible | el host, el stack, `.env`, el webhook |
| Administrador de Grafana | cuenta local `admin`, contraseña en `/opt/piedrazul-obs/.env` | solo por el túnel (la ruta pública no enruta `/login` ni deja pasar `Authorization`) | usuarios, fuentes de datos, configuración de Grafana |
| Editor | correos de `obs_grafana_access_editors` | Access | dashboards (no fuentes de datos ni usuarios) |
| Viewer | correos de `obs_grafana_access_viewers` | Access | lectura |

El administrador local tiene el correo reservado `admin@piedrazul-obs.invalid` (aplicado al crear la
base de Grafana). Grafana vincula un JWT a un usuario existente con el mismo correo; ninguna
identidad de Access puede tener un dominio `.invalid` y el rol rechaza listas con `.invalid` o
sin dominio (`admin@localhost`, el correo por defecto de una base creada antes de S3). Probado:
con esa vinculación, un JWT autorizado con el correo del administrador cambia su login y rompe la
recuperación local; por eso la lista nunca debe contener el correo de una cuenta local.

## Recuperación por túnel

Independiente de Cloudflare, del DNS y de Traefik:

```bash
ssh -L 3000:127.0.0.1:3000 ansible@<host>
```

y abrir `http://localhost:3000/login` con `admin` y la contraseña de `/opt/piedrazul-obs/.env`
(`sudo`). Cambiar la contraseña:

```bash
cd /opt/piedrazul-obs
sudo docker compose exec grafana grafana cli admin reset-admin-password '<nueva>'
```

y reflejarla en `.env`.

**Por qué funciona con el acceso público habilitado.** La `root_url` pasa a ser
`https://grafana.piedrazul.org/` y las cookies son `Secure`, pero:

- Grafana redirige a `/login` con rutas relativas al origen de la petición: por el túnel el
  navegador se queda en `http://localhost:3000` (no salta al nombre público);
- `http://localhost` y `http://127.0.0.1` son contextos seguros para Chromium y Firefox, que
  guardan y envían cookies `Secure` allí;
- el CSRF de Grafana compara el origen con el `Host` de la petición (el del túnel).

No hace falta quitar `Secure`: quitarlo expondría la cookie de sesión local si alguna vez viajara
por http fuera del loopback, sin ganar nada en el túnel.

**Probado en local con navegadores reales** (`grafana-access-check.sh`, Playwright 1.55: Chromium
140 y Firefox 141; el contenedor reenvía su `127.0.0.1:3000` a Grafana, como `ssh -L`), en
`http://localhost:3000` y en `http://127.0.0.1:3000`, con el acceso público habilitado: redirección
a `/login` en el mismo origen, formulario, cookie `grafana_session` guardada (`Secure`,
`HttpOnly`, `SameSite=Strict`), sesión de administrador del servidor, una acción de
administración, navegación a un dashboard, recarga y logout. También por API: login con el JWKS
inalcanzable y tras un intento de vincular el correo del administrador.

**No probado aquí** (verificación en vivo, ver [Activación](#activación)): el transporte SSH real
(`ssh -L` desde la máquina del operador), el navegador de escritorio del operador (versión y
extensiones propias) y Safari/WebKit.

## Estado deseado

El estado deseado no secreto se guarda en `/opt/piedrazul-obs/.env` (root, 0600) y es
**persistente**: las variables del rol valen `~` por defecto, que significa "conservar lo que el host
ya tiene". Cada valor se resuelve así: dado en este run (`-e`, `host_vars`) > guardado en el host >
inicial. Un run de mantenimiento de `observability.yml` sin `-e` no activa ni desactiva nada.
Desactivar es explícito y conserva identificadores y listas; volver a habilitar solo necesita el
interruptor. El webhook de Discord no es parte de este estado ([Alertas](alertas.md#credencial)).

| Variable del rol | `.env` | Inicial | Validación antes de tocar nada |
|---|---|---|---|
| `obs_grafana_access_enabled` | `GRAFANA_ACCESS_ENABLED` | false | booleano |
| `obs_grafana_public_domain` | `GRAFANA_PUBLIC_DOMAIN` (y `GRAFANA_ROOT_URL=https://<dominio>/` solo habilitado) | vacío | nombre DNS en minúsculas |
| `obs_grafana_access_team_domain` | `GRAFANA_ACCESS_TEAM_DOMAIN` | vacío | `<equipo>.cloudflareaccess.com` |
| `obs_grafana_access_aud` | `GRAFANA_ACCESS_AUD` | vacío | 64 hex |
| `obs_grafana_access_editors`, `obs_grafana_access_viewers` | `GRAFANA_ACCESS_EDITORS`, `GRAFANA_ACCESS_VIEWERS` | vacías | listas; al menos un correo habilitado; minúsculas, dominio real, sin `.invalid`, sin repetir |
| — (derivado) | `COMPOSE_FILE` | `compose.yml` | con el acceso: `compose.yml:compose.access.yml` |
| `obs_edge_network` | `OBS_EDGE_NETWORK` | `piedrazul_obs_edge` | con el acceso: debe existir y ser interna |

Los correos de los operadores son personales: se pasan una vez al activar (`-e`) y quedan solo en
el `.env` del host, nunca en el repositorio ni en el inventario versionado. `COMPOSE_FILE` hace que
un `docker compose` manual en `/opt/piedrazul-obs` use los mismos archivos que el rol.

**Modo privado** (acceso deshabilitado, por defecto): solo `compose.yml`; Grafana en `obs_net` y el
loopback; no exige `piedrazul_obs_edge`. Es el modo de un host con S1/S2 sin la aplicación F2
completa (el laboratorio `obs`). **Modo público**: además `compose.access.yml`, que une Grafana a
la red interna que crea `compose/traefik.yml` de la aplicación; el rol falla sin cambiar nada si la
red no existe.

El router de Traefik lo controla, aparte, `grafana_public_domain` en el inventario de la
aplicación (`group_vars/all.yml`, vacío): con él vacío no hay router aunque Grafana acepte JWT; con
el router y sin JWT habilitado, la ruta no tiene ninguna forma de autenticarse (falla cerrado).

## Activación

Orden; cada paso externo pertenece a una unidad de trabajo autorizada aparte. Un host puede quedar
en modo privado indefinidamente (pasos 2 y siguientes solo cuando se quiera el acceso público).

1. **Cloudflare: verificar lo preparado, no recrearlo.** En Zero Trust → Access → Applications,
   la aplicación `grafana` (dominio `grafana.piedrazul.org`, tipo *self-hosted*), su política
   `Grafana Operators` (las dos identidades) y la duración de sesión (24 h); copiar el AUD Tag de
   esa aplicación y confirmar el dominio del equipo (`piedrazul.cloudflareaccess.com`, en Settings
   → Custom Pages / Team domain). Si algo difiere de lo informado, decidir antes de seguir; no
   crear una segunda aplicación.
2. **Aplicación** (converge/deploy de esta versión): `compose/traefik.yml` crea la red interna
   `piedrazul_obs_edge` y une a Traefik. Sin router todavía.
3. **Stack con Access** (una sola vez; queda guardado en el host):

   ```bash
   cd infra/ansible
   ansible-playbook playbooks/observability.yml \
     -e obs_grafana_access_enabled=true \
     -e obs_grafana_public_domain=grafana.piedrazul.org \
     -e obs_grafana_access_team_domain=piedrazul.cloudflareaccess.com \
     -e obs_grafana_access_aud=<AUD Tag de la aplicación grafana> \
     -e '{"obs_grafana_access_viewers": ["<correo 1>", "<correo 2>"], "obs_grafana_access_editors": []}'
   ```

   Las dos identidades de `Grafana Operators` van en Viewer o en Editor según el rol que deban
   tener en Grafana (la política de Access no da roles). La verificación del rol comprueba en
   Grafana el modo JWT, el JWKS, `role_attribute_strict`, la ausencia de administrador por JWT y la
   `root_url`. Después, `observability.yml` sin `-e` lo conserva.
4. **DNS (externo)**: registro `grafana` proxied hacia el servidor (AOP ya aplica a la zona).
5. **Router**: `grafana_public_domain: grafana.piedrazul.org` en el inventario y converge.
6. **Verificación en vivo** (pendiente, no hecha): con cada identidad de la lista → Grafana con su
   rol; con una identidad que Access permite pero fuera de la lista → 403; sin sesión de Access →
   pantalla de Access; `https://grafana.piedrazul.org/login` → 404; petición directa a la IP del
   servidor con `Host: grafana.piedrazul.org` → sin handshake; **recuperación**: `ssh -L` real desde
   la máquina del operador y login local en su navegador con el acceso público habilitado;
   registrar el efecto de revocar una sesión en Access.

## Desactivación

Vaciar `grafana_public_domain` y converge (sin router), y repetir `observability.yml` con
`-e obs_grafana_access_enabled=false` (Grafana sin JWT, modo privado; los runs siguientes sin `-e`
lo mantienen). Identificadores y listas quedan guardados: volver a habilitar es
`-e obs_grafana_access_enabled=true`. Los usuarios creados por JWT quedan en la base de Grafana sin
acceso; borrarlos desde el túnel si se quiere. La red de entrada de la aplicación se conserva.

## Limitaciones

- **Sesión y revocación.** Grafana no crea sesión: valida el JWT en cada petición, así que el
  acceso dura lo que dure el JWT que Access emite (la duración de sesión de la aplicación de
  Access). Revocar en Access deja de entregar JWT nuevos en el borde; un JWT ya emitido sigue
  siendo válido para Grafana hasta su `exp` si llegara al origen (solo puede llegar por
  Cloudflare). Con la sesión de 24 h preparada, ese plazo es de hasta 24 h. Quitar un correo de la
  lista y repetir el playbook (que recrea Grafana) le quita el acceso en la petición siguiente,
  también con un JWT vigente (probado): es la revocación inmediata del lado de Grafana. Verificar
  en vivo la revocación en Access.
- **Rotación de claves de Access.** Una clave nueva se acepta cuando vence la caché del JWKS
  (≤ 5 min tras publicarse; probado con caché de 20 s). Si Access firmara con una clave antes de
  publicarla, habría rechazos (401) durante ese intervalo; el túnel no se ve afectado.
- **JWKS inalcanzable.** Mientras la caché esté vigente Grafana sigue aceptando; vencida, rechaza
  los JWT que no validó antes (falla cerrado). El túnel sigue funcionando.
- **Correos.** La lista compara en minúsculas y exactos: un alias o un correo distinto del que
  entrega el proveedor de identidad de Access no entra (403).
- **Usuarios.** Grafana no borra a quien sale de la lista; no puede entrar, pero su fila queda.
- **Red de entrada.** Grafana alcanza a Traefik por `piedrazul_obs_edge` (sus entrypoints, como
  cualquier cliente, y su métrica `:8082`); no alcanza backend, Keycloak ni PostgreSQL.
- **Bases antiguas.** `GF_SECURITY_ADMIN_EMAIL` solo se aplica al crear la base: un Grafana
  creado antes conserva `admin@localhost`, igual protegido porque el rol rechaza ese correo en las
  listas.

## Pruebas

`infra/tests/grafana-access-check.sh` (Docker, sin red externa): contratos de Compose, Traefik y
el rol; rechazos del rol y de las rutas sin escribir; JWT válidos, inválidos, expirados, sin `exp`,
de otro emisor, audiencia o tipo, `alg=none`, HS256, clave ajena, correos fuera de la lista, con
comas, sin correo y del administrador; permisos de Viewer y Editor; Traefik real con AOP, `/login`,
Basic y cookies descartadas; navegadores reales (túnel y ruta pública); revocación por la lista,
rotación, JWKS caído y recuperación local. `STATIC_ONLY=1` omite los contenedores;
`BROWSER_CHECK=0` omite los navegadores (y lo informa como no demostrado). La primera vez construye
la imagen local `piedrazul-browsercheck:pw1.55.0` (base de Playwright fijada por digest más el
paquete `playwright==1.55.0` de PyPI).

`infra/tests/obs-role-state-check.sh`: el estado deseado persistente con las tareas reales del rol
(inicial, activación, mantenimiento sin `-e`, rotación del webhook, desactivación, rehabilitación,
modo privado sin red de entrada, rechazos sin escribir).

## Problemas frecuentes

| Síntoma | Causa probable | Acción |
|---|---|---|
| 403 `Invalid Role in claim` | correo fuera de las listas, en otra forma o con mayúsculas en la lista | comparar con el correo del JWT (`/cdn-cgi/access/get-identity` en el navegador) y repetir el playbook |
| 401 `Failed to verify JWT` | AUD o equipo equivocados, JWT vencido, clave recién rotada | revisar `GRAFANA_ACCESS_AUD`/`TEAM_DOMAIN`; esperar ≤ 5 min tras una rotación |
| 401 en todo con Access correcto | Grafana no alcanza el JWKS (salida de `obs_net`, DNS) y la caché venció | `docker compose logs grafana`; recuperar por túnel |
| 404 en `grafana.piedrazul.org` | sin router (`grafana_public_domain` vacío) o `/login` | habilitar el router; el login local es solo por túnel |
| 502 en `grafana.piedrazul.org` | Grafana caído o fuera de `piedrazul_obs_edge` | `docker compose ps` en `/opt/piedrazul-obs` |
| el rol falla con `No existe la red interna piedrazul_obs_edge` | acceso público pedido o guardado, y la aplicación no se desplegó con esta versión | converge/deploy de la aplicación primero, o `-e obs_grafana_access_enabled=false` (modo privado) |
| el acceso se desactivó solo tras un run | no ocurre con esta versión (estado persistente); revisar si alguien pasó `-e ...=false` | `grep GRAFANA_ACCESS /opt/piedrazul-obs/.env` (sudo) |
