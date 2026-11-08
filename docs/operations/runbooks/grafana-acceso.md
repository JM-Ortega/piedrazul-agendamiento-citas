# Acceso de operadores a Grafana

## Propósito

Usar Grafana a diario por `https://grafana.piedrazul.org` detrás de Cloudflare Access, con Grafana
validando el JWT de Access y autorizando por su propia lista, y conservar una recuperación
administrativa independiente de Cloudflare por túnel SSH y el administrador local.

**Estado.** El repositorio implementa y prueba en local la configuración (Grafana, Traefik, rol de
Ansible, workflow). Fuera del repositorio, el operador administra en Cloudflare, y confirmó como
valores operativos definitivos, la aplicación de Access `grafana` para `grafana.piedrazul.org` (política
`Grafana Operators`, sesión de 24 h), el equipo `piedrazul.cloudflareaccess.com`, su emisor
(`https://piedrazul.cloudflareaccess.com`), su JWKS (`/cdn-cgi/access/certs`), el AUD Tag de la
aplicación y el DNS. La cadena no los crea ni los modifica: consume sus identificadores, que no son
secretos, desde el inventario versionado (`group_vars/all.yml`: `obs_grafana_access_enabled`,
`obs_grafana_public_domain`, `obs_grafana_access_team_domain`, `obs_grafana_access_aud`). El router
de Traefik (`grafana_public_domain`) se deriva del mismo dominio y del mismo interruptor: una sola
fuente para los dos consumidores. Falta la verificación en vivo (abajo).

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
| Autorización | `lower(email)` en la lista Editor → Editor; en la lista Viewer → Viewer; otro, con comas o sin correo → sin rol (`role_attribute_strict`) | 403 |

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
| Editor | correos de `GRAFANA_EDITOR_EMAILS` | Access | dashboards (no fuentes de datos ni usuarios) |
| Viewer | correos de `GRAFANA_VIEWER_EMAILS` | Access | lectura |

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

**No probado aquí** (verificación en vivo, ver [Activación y desactivación](#activación-y-desactivación)): el transporte SSH real
(`ssh -L` desde la máquina del operador), el navegador de escritorio del operador (versión y
extensiones propias) y Safari/WebKit.

## Listas de operadores

Las listas de autorización de Grafana son dos **secretos** del environment `production-hetzner`
(no variables: el repositorio es público y los logs de Actions también):

| secreto | rol en Grafana |
|---|---|
| `GRAFANA_VIEWER_EMAILS` | Viewer (lectura) |
| `GRAFANA_EDITOR_EMAILS` | Editor (dashboards) |

**Formato.** Correos separados por comas, con espacios alrededor permitidos, en minúsculas y con
dominio real (sin `.invalid`), por ejemplo `persona.uno@unicauca.edu.co, persona.dos@unicauca.edu.co`;
o exactamente `none` para una lista vacía (GitHub no guarda secretos vacíos). Se rechaza, antes de
cualquier conexión al servidor y sin mostrar ningún correo: un secreto ausente, una entrada vacía
(coma final), mayúsculas, un correo repetido, el mismo correo en las dos listas, más de 50 entradas
o `none` mezclado con correos. Los secretos se escriben siempre completos: GitHub no los muestra
después, así que el operador conserva la lista vigente fuera del repositorio.

**Son autoritativas en cada run** que toca el stack: el rol nunca conserva una lista anterior del
host, y sin uno de los secretos el run falla sin cambiar nada. Las listas llegan a Grafana en su
archivo de configuración (`/opt/piedrazul-obs/secrets/grafana_access.ini`, del UID de Grafana, solo
legible por él; `GF_PATHS_CONFIG`), no en el entorno del contenedor, en `.env` ni en etiquetas. El
resumen del run solo muestra cuántas entradas tiene cada lista.

**Aplicar un cambio de las listas** (no dispara ningún workflow por sí solo): Actions →
*Producción — Hetzner* → *Run workflow* en `main`, con `observability=grafana-access` y todo lo demás
en `false`/vacío. Esa operación, con la aprobación del environment, la identidad del host y la
frescura de `main`:

1. valida las listas sin red;
2. escribe el archivo de acceso y recrea **solo Grafana** (sin dependencias: no espera a Prometheus)
   con los archivos de Compose que ya tiene el host;
3. verifica en Grafana el modo JWT, el rol estricto, la ausencia de administrador por JWT y que la
   expresión cargada sea la de las listas pedidas (comparada sin mostrarla).

No depende de la salud de Prometheus, Loki, Alloy, Alertmanager ni de la aplicación, no escanea
imágenes y no avanza el progreso del stack. Exige el stack ya instalado por una operación `full`.
Cualquier operación `full` aplica también las listas vigentes.

**Revocación.** Quitar un correo y aplicar le quita el rol en la petición siguiente a la recreación
de Grafana, también con un JWT de Access todavía vigente (probado). Con las dos listas en `none`,
nadie entra por JWT; Cloudflare Access, el modo JWT de Grafana y el administrador local por túnel no
cambian. Cambiar una lista **no** agrega ni quita a nadie de la política de Access: entrar por
Access sigue siendo una operación aparte en Cloudflare, y una sesión de Access ya emitida sigue
vigente hasta su vencimiento (hasta 24 h) aunque Grafana ya le niegue el rol.

Los roles de cada persona los decide quien administra el servicio; el repositorio no los contiene.

## Estado deseado

El resto del estado no secreto se guarda en `/opt/piedrazul-obs/.env` (root, 0600). En producción el
inventario lo da siempre (`group_vars/all.yml`); en una ejecución manual sin inventario, las
variables del rol valen `~` ("conservar lo que el host ya tiene"): dado en este run (`-e`,
inventario) > guardado en el host > inicial. Desactivar es explícito y conserva los identificadores.
El webhook de Discord y las listas de correos no son parte de este estado ([Alertas](alertas.md#credencial),
[Listas de operadores](#listas-de-operadores)).

| Variable del rol | `.env` | Inicial | Validación antes de tocar nada |
|---|---|---|---|
| `obs_grafana_access_enabled` | `GRAFANA_ACCESS_ENABLED` | false | booleano |
| `obs_grafana_public_domain` | `GRAFANA_PUBLIC_DOMAIN` (y `GRAFANA_ROOT_URL=https://<dominio>/` solo habilitado) | vacío | nombre DNS en minúsculas |
| `obs_grafana_access_team_domain` | `GRAFANA_ACCESS_TEAM_DOMAIN` | vacío | `<equipo>.cloudflareaccess.com` |
| `obs_grafana_access_aud` | `GRAFANA_ACCESS_AUD` | vacío | 64 hex |
| — (derivado) | `COMPOSE_FILE` | `compose.yml` | con el acceso: `compose.yml:compose.access.yml` |
| `obs_edge_network` | `OBS_EDGE_NETWORK` | `piedrazul_obs_edge` | con el acceso: debe existir y ser interna |

`COMPOSE_FILE` hace que un `docker compose` manual en `/opt/piedrazul-obs` use los mismos archivos
que el rol. Una versión anterior guardaba las listas en `.env` (`GRAFANA_ACCESS_EDITORS`,
`GRAFANA_ACCESS_VIEWERS`): el primer run las retira.

**Modo privado** (acceso deshabilitado): solo `compose.yml`; Grafana en `obs_net` y el loopback; no
exige `piedrazul_obs_edge`. **Modo público** (el de producción): además `compose.access.yml`, que une
Grafana a la red interna que crea `compose/traefik.yml` de la aplicación; el rol falla sin cambiar
nada si la red no existe.

## Activación y desactivación

En producción el acceso está habilitado en el inventario: Run I lo aplica en orden (el converge de
la aplicación crea la red interna y el router; después el job *Observabilidad* configura Grafana con
el JWT de Access). Cambiar el interruptor, el dominio o el AUD es un cambio del inventario, que un
push aplica a los dos consumidores (converge y stack). Desactivar es
`obs_grafana_access_enabled: false` en el inventario: sin router y Grafana sin JWT (solo túnel);
identificadores guardados. Los usuarios creados por JWT quedan en la base de Grafana sin acceso;
borrarlos desde el túnel si se quiere.

**Verificación en vivo** (pendiente, no hecha): con cada identidad de las listas → Grafana con su rol;
con una identidad que Access permite pero fuera de las listas → 403; sin sesión de Access → pantalla
de Access; `https://grafana.piedrazul.org/login` → 404; petición directa a la IP del servidor con
`Host: grafana.piedrazul.org` → sin handshake; **recuperación**: `ssh -L` real desde la máquina del
operador y login local en su navegador con el acceso público habilitado; una operación
`grafana-access` que quita una identidad; registrar el efecto de revocar una sesión en Access.

## Limitaciones

- **Sesión y revocación.** Grafana no crea sesión: valida el JWT en cada petición, así que el
  acceso dura lo que dure el JWT que Access emite (la duración de sesión de la aplicación de
  Access). Revocar en Access deja de entregar JWT nuevos en el borde; un JWT ya emitido sigue
  siendo válido para Grafana hasta su `exp` si llegara al origen (solo puede llegar por
  Cloudflare). Con la sesión de 24 h preparada, ese plazo es de hasta 24 h. Quitar un correo de la
  lista y aplicar `grafana-access` (que recrea Grafana) le quita el acceso en la petición siguiente,
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
- **Archivo de configuración de Grafana.** `GF_PATHS_CONFIG` apunta al archivo de acceso: la
  configuración de la imagen (`/etc/grafana/grafana.ini`) no se carga. En Grafana 13.3.0 ese archivo
  solo fija valores de alerting unificado y reglas de grabación, deshabilitados o por defecto aquí
  (comprobado al fijar la versión); revisarlo al cambiar de imagen.

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
| 401 `Failed to verify JWT` | AUD o equipo equivocados, JWT vencido, clave recién rotada | revisar `obs_grafana_access_aud`/`obs_grafana_access_team_domain` del inventario; esperar ≤ 5 min tras una rotación |
| 403 para un operador que debería entrar | su correo no está en `GRAFANA_VIEWER_EMAILS`/`GRAFANA_EDITOR_EMAILS` tal como lo entrega el proveedor de identidad, o el cambio no se aplicó | corregir el secreto y correr `observability=grafana-access` |
| el run falla con `LISTAS DE GRAFANA` | secreto ausente o con formato inválido (el mensaje dice cuál y en qué posición, sin el correo) | corregir el secreto; el host no cambió |
| 401 en todo con Access correcto | Grafana no alcanza el JWKS (salida de `obs_net`, DNS) y la caché venció | `docker compose logs grafana`; recuperar por túnel |
| 404 en `grafana.piedrazul.org` | sin router (`grafana_public_domain` vacío) o `/login` | habilitar el router; el login local es solo por túnel |
| 502 en `grafana.piedrazul.org` | Grafana caído o fuera de `piedrazul_obs_edge` | `docker compose ps` en `/opt/piedrazul-obs` |
| el rol falla con `No existe la red interna piedrazul_obs_edge` | acceso público pedido o guardado, y la aplicación no se desplegó con esta versión | converge/deploy de la aplicación primero, o `-e obs_grafana_access_enabled=false` (modo privado) |
| el acceso se desactivó solo tras un run | no ocurre con esta versión (estado persistente); revisar si alguien pasó `-e ...=false` | `grep GRAFANA_ACCESS /opt/piedrazul-obs/.env` (sudo) |
