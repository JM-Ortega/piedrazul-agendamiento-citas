# Ansible — Piedrazul

Infraestructura de configuración y despliegue del sistema de agendamiento de citas médicas Piedrazul. Este documento explica la arquitectura completa de Ansible, cómo funciona cada pieza, y cómo extender el sistema cuando sea necesario.

---

## Estructura de directorios

```
infra/ansible/
├── ansible.cfg                          # Configuración global de Ansible
├── requirements.yml                     # Colecciones externas requeridas
├── inventories/
│   └── prod/
│       ├── hosts.yml                    # Definición de hosts (sin IP — se inyecta en runtime)
│       ├── group_vars/
│       │   └── all.yml                  # Variables compartidas por todos los entornos
│       └── host_vars/
│           ├── vps.yml                  # Variables específicas de Hetzner
│           └── vps_oci.yml              # Variables específicas de OCI
├── playbooks/
│   ├── converge.yml                     # Configura el servidor + levanta la app
│   ├── deploy.yml                       # Rollout de backend y postgres por identidad exacta
│   ├── verify-release.yml               # Verifica identidad en ejecución o health (solo lectura)
│   ├── record-release.yml               # Registra el último despliegue exitoso
│   └── keycloak-admin-preflight.yml     # Valida en el runner la contraseña inicial antes del Apply
└── roles/
    ├── common/                          # Paquetes base y timezone
    ├── docker_host/                     # Instalación y configuración de Docker
    ├── hardening/                       # Hardening del servidor
    └── app/                             # Gestión del stack de la aplicación
        ├── tasks/
        │   ├── main.yml                 # Punto de entrada (importa setup.yml)
        │   ├── setup.yml                # Copia archivos, genera .env y rutas de Traefik, postgres, Traefik
        │   ├── keycloak_credentials.yml # Toma y valida las credenciales técnicas de Keycloak (setup)
        │   ├── keycloak_backend_delivery.yml # Verifica que el backend recibe su secreto intacto (setup)
        │   ├── identity_seed_inputs.yml # Valida el bootstrap del primer ADMIN de negocio antes de .env (setup)
        │   ├── identity_seed_image_gate.yml # Solo una imagen con el contrato create-only recibe el bootstrap (setup y deploy)
        │   ├── identity_seed_delivery.yml # Entrega exacta (habilitado) o vacía (deshabilitado) al backend (setup)
        │   ├── identity_seed_runtime.yml # Resultado del bootstrap o retiro del secreto en el backend (refresh y deploy)
        │   ├── keycloak_lifecycle.yml   # Ciclo técnico de Keycloak: P1/P2/P3 (converge)
        │   ├── keycloak_state.yml       # Clasificación por el estado de Keycloak (base + API)
        │   ├── keycloak_bootstrap.yml   # Evento con autoridad temporal (P2 o recuperación P3)
        │   ├── deploy.yml               # Rollout de backend y postgres (con migración previa)
        │   ├── db_converge.yml          # postgres + reconciliación de autoridades de la base (setup y deploy)
        │   ├── migrate.yml              # Migración de esquema de un solo uso (deploy)
        │   ├── refresh_backend.yml      # Entrega el .env vigente al backend que ya corre (converge)
        │   ├── release_images.yml       # Valida y descarga por digest las imágenes propias (setup y deploy)
        │   ├── verify_running_images.yml # Identidad exacta en ejecución
        │   ├── verify_health.yml        # Health de los contenedores propios
        │   └── record_release.yml       # Escribe el último despliegue exitoso
        ├── files/
        │   ├── keycloak-state.sql       # Lectura del estado de Keycloak en su base (solo lectura)
        │   └── keycloak-admin.sh        # kcadm dentro del contenedor (secretos solo por entorno)
        ├── handlers/
        │   └── main.yml                 # Handler de restart del stack
        └── templates/
            └── env.j2                   # Template del archivo .env de producción
```

---

## Colecciones requeridas

```yaml
# requirements.yml
collections:
  - name: community.docker    # docker_compose_v2
    version: "5.2.0"
  - name: ansible.posix       # sysctl, authorized_key
    version: "2.1.0"
  - name: community.general   # timezone
    version: "12.5.0"
```

Instalar antes de usar:

```bash
cd infra/ansible
ansible-galaxy collection install -r requirements.yml
```

---

## Inventario

### `hosts.yml`

Define el host `vps` sin `ansible_host` — la IP se inyecta en runtime por el workflow de GitHub Actions desde el output de Terraform.

```yaml
all:
  hosts:
    vps:
```

En CI el workflow genera un archivo de inventario temporal:

```bash
echo "vps ansible_host=${SERVER_IP}" > "$RUNNER_TEMP/hosts.runtime.yml"
ansible-playbook \
  -i infra/ansible/inventories/prod \
  -i "$RUNNER_TEMP/hosts.runtime.yml" \
  infra/ansible/playbooks/converge.yml
```

Para OCI el host se llama `vps_oci` y el workflow usa `host_vars/vps_oci.yml`.

### `group_vars/all.yml`

Variables compartidas por todos los entornos — valores que no cambian entre Hetzner y OCI:

```yaml
app_dir: /opt/piedrazul
compose_project_name: piedrazul
compose_src_dir: "{{ playbook_dir }}/../../compose"
release_state_file: "{{ app_dir }}/last-success.env"
kc_realm: piedrazul
kc_port: 8180
kc_backend_client_id: piedrazul-backend
kc_automation_client_id: piedrazul-keycloak-automation
kc_automation_roles: [piedrazul-realm:manage-clients, piedrazul-realm:view-clients]
kc_temp_admin_prefix: piedrazul-tmp-admin
kc_temp_admin_owned_regex: "^{{ kc_temp_admin_prefix }}-[0-9]{14}-[a-z0-9]{8}$"
kc_realm_file: "{{ repo_root }}/infra/keycloak/realm/piedrazul-realm.json"
```

### `host_vars/vps.yml` (Hetzner)

```yaml
kc_hostname: "auth.{{ base_domain }}"
api_public_domain: "api.{{ base_domain }}"
frontend_public_domain: "{{ base_domain }}"
acme_email: jortegan@unicauca.edu.co

cors_allowed_origins: "https://{{ frontend_public_domain }}"
```

`frontend_public_domain` también llega a Keycloak: `env.j2` escribe `FRONTEND_PUBLIC_URL=https://<frontend_public_domain>`, que `compose/keycloak.yml` exige y el tema de login usa para los enlaces al frontend (ver el [README del tema](../keycloak/themes/piedrazul/keycloakify-starter/README.md)).

### `host_vars/vps_oci.yml` (OCI)

```yaml
kc_hostname: auth.piedrazul-oci.narvaezlab.dev
api_public_domain: api.piedrazul-oci.narvaezlab.dev
acme_email: jortegan@unicauca.edu.co
```

---

## Playbooks

### `converge.yml` — configuración completa del servidor

Corre en el primer deploy y en cualquier momento para reconciliar el estado del servidor. Es idempotente — seguro de ejecutar N veces.

```
converge.yml
├── role: common        → apt update, paquetes base, timezone
├── role: docker_host   → instala Docker CE + Compose plugin, daemon.json
├── role: hardening     → fail2ban, sysctl, deshabilita servicios innecesarios
├── app/setup.yml       → copia archivos, genera .env y rutas de Traefik, postgres + reconciliación, Traefik
├── app/keycloak_lifecycle.yml → ciclo técnico de Keycloak (P1/P2/P3, ver abajo)
└── app/refresh_backend.yml → recrea el backend en ejecución si cambió su configuración (sin imagen nueva)
```

Ejecutar manualmente:

```bash
ansible-playbook \
  -i inventories/prod \
  -i /tmp/hosts.runtime.yml \
  playbooks/converge.yml
```

### `deploy.yml` — rollout de las imágenes propias

Actualiza backend y postgres. No reconfigura el servidor ni toca Keycloak, y no registra el último éxito. `backend_image_ref` y `postgres_image_ref` son obligatorios y deben ser referencias exactas `repo@sha256:<manifiesto linux/amd64>`; los elige el workflow de producción (ver [Identidad de las imágenes propias](#identidad-de-las-imágenes-propias)).

```bash
ansible-playbook \
  -i inventories/prod \
  -i /tmp/hosts.runtime.yml \
  -e "backend_image_ref=ghcr.io/jm-ortega/piedrazul-agendamiento-citas/backend@sha256:<digest>" \
  -e "postgres_image_ref=ghcr.io/jm-ortega/piedrazul-agendamiento-citas/postgres@sha256:<digest>" \
  -e app_migrate=true \
  playbooks/deploy.yml
```

`app_migrate` es obligatorio: `true` migra el esquema con `backend_image_ref` antes de arrancarlo; `false` no migra (el workflow lo usa para rollback y reuso, ver [Base de datos](#base-de-datos-autoridades-reconciliación-y-migración)). `converge.yml` recibe las dos referencias, sin `app_migrate`.

### `verify-release.yml` y `record-release.yml`

`verify-release.yml` no modifica nada: con `release_check=identity` comprueba que los contenedores corren la identidad exacta esperada; con `release_check=health`, que quedaron `healthy`. `record-release.yml` escribe el último despliegue exitoso (ver abajo). El workflow los corre como pasos separados para que un fallo de identidad, de health o de registro se vea como tal.

---

## Roles

### `common`

- Actualiza cache de apt (`cache_valid_time: 3600`)
- Instala `curl`, `gnupg`, `ca-certificates`, `lsb-release`
- Configura timezone a `America/Bogota`

### `docker_host`

- Elimina paquetes conflictivos (`docker.io`, `docker-compose`, etc.)
- Detecta arquitectura del servidor (`amd64` para Hetzner x86, `arm64` para OCI ARM)
- Agrega GPG key y repositorio oficial de Docker
- Instala `docker-ce`, `docker-ce-cli`, `containerd.io`, `docker-buildx-plugin`, `docker-compose-plugin`
- Configura `daemon.json` con log rotation (`max-size: 10m`, `max-file: 3`), `live-restore: true`, y `nofile: 65536`
- Agrega usuario `ansible` al grupo `docker`

### `hardening`

- Verifica que `fail2ban` esté activo
- Deshabilita `snapd` y `apport` si existen
- Aplica parámetros de kernel via `sysctl`: `tcp_syncookies`, `rp_filter`, `icmp_echo_ignore_broadcasts`, `dmesg_restrict`

### `app`

El rol principal. Se invoca desde los playbooks con `tasks_from` para controlar qué fase ejecutar.

#### `setup.yml`

Corre en cada `converge`. Pasos:

1. Crea `/opt/piedrazul/`
2. `keycloak_credentials.yml`: toma `KC_BACKEND_CLIENT_SECRET` y `KC_AUTOMATION_CLIENT_SECRET` del entorno del runner y falla, nombrando solo la regla, si alguno está vacío, si son iguales (el de automatización no debe persistirse en el host) o si coinciden con un valor del repositorio (`.env.example`, realm). Sin lista de caracteres prohibidos ni requisito de formato nuevo: el valor (con el `trim` de extremos que ya se aplicaba) llega a Keycloak en JSON armado por Ansible y al backend por `.env` con escape
3. Valida `frontend_public_domain` (nombre DNS en minúsculas; si no, falla sin copiar Compose ni escribir `.env`) y copia `infra/compose/` → `/opt/piedrazul/`
4. Copia `infra/keycloak/` → `/opt/piedrazul/keycloak/` (realm JSON + theme JAR) e `infra/traefik/` → `/opt/piedrazul/traefik/` (opciones TLS de AOP y CA de Cloudflare); `traefik_routes.yml` genera `traefik/dynamic/routes.yml` (ver [Traefik](#traefik))
5. Toma del entorno del runner las contraseñas de la base (`POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `MIGRATION_DB_PASSWORD`, `KC_DB_PASSWORD`) y falla, nombrando solo las claves, si alguna está vacía o contiene comilla simple o salto de línea
6. Genera `.env` desde `env.j2`
7. Valida y descarga por digest las imágenes propias (`release_images.yml`), Keycloak (`keycloak_image.yml`) y Traefik (`traefik_image.yml`) → `image.env`
   - `keycloak_backend_delivery.yml`: comprueba con `docker compose config` que el backend recibiría `KC_BACKEND_CLIENT_SECRET` exactamente igual al declarado (en `.env` va entre comillas dobles con `\ " $` escapados); si no, falla sin mostrar el valor
8. `db_converge.yml`: postgres con el `.env` vigente + reconciliación de autoridades
9. Levanta Traefik (`docker_compose_v2 state: present`, imagen ya descargada por digest). No arranca Keycloak (lo hace `keycloak_lifecycle.yml`), el backend ni migra

#### Traefik

Traefik enruta solo con el **file provider**: no tiene Docker provider ni socket de Docker montado, y ningún servicio lleva labels `traefik.*`. Lee `/opt/piedrazul/traefik/dynamic/` (montado de solo lectura, con `watch`):

| Archivo | Contenido | Origen |
|---|---|---|
| `tls.yml` | opción TLS `cloudflare-aop` (certificado de cliente de Cloudflare obligatorio, CA en `traefik/certs/`) | `infra/traefik/dynamic/tls.yml`, copiado tal cual |
| `routes.yml` | routers `backend` (`Host(api_public_domain)`) y `keycloak` (`Host(kc_hostname)`), entrypoint `websecure`, TLS `cloudflare-aop` + resolver `letsencrypt`; servicios `http://backend:<server_port>` y `http://keycloak:<kc_port>` | generado por `traefik_routes.yml` desde `templates/traefik-routes.yml.j2` con las mismas variables del inventario que `.env` |

- Para cambiar un host o puerto se cambia el inventario (`host_vars`/`group_vars`) y se corre converge; `routes.yml` no se edita en el servidor (converge lo sobrescribe). Antes de escribirlo, `traefik_routes.yml` valida las entradas (nombres DNS en minúsculas, distintos; puertos numéricos) y el documento generado; si algo falla, converge se detiene sin tocar el archivo vigente. Traefik recarga el cambio sin reiniciarse.
- Los servicios apuntan al nombre del contenedor en `piedrazul_net`; Traefik lo resuelve en cada conexión nueva, así que recrear o reiniciar backend o Keycloak no deja rutas obsoletas ni necesita la API de Docker. Mientras un upstream no existe o no responde, su host devuelve `502` (antes, con el Docker provider, `404`); un host sin ruta devuelve `404`.
- `--core.strictTLSOptions=true`: si dos routers del mismo host pidieran opciones TLS distintas, Traefik desactiva esos routers en vez de servirlos con las opciones por defecto (sin certificado de cliente de AOP).
- HTTP (`:80`) redirige de forma permanente a HTTPS (`301` para GET/HEAD, `308` para otros métodos). Los certificados los emite Let's Encrypt por DNS de Cloudflare (`CF_DNS_API_TOKEN`) y persisten en el volumen `traefik_letsencrypt`, que sobrevive a recrear el contenedor.
- `X-Forwarded-*`: los entrypoints no confían en cabeceras entrantes (sin `forwardedHeaders.trustedIPs`); Traefik las reemplaza con lo que ve (Proto `https`, Host pedido, For = IP del par TCP, es decir, Cloudflare).
- Un archivo dinámico malformado lo registra Traefik como error en su log: en caliente conserva la última configuración válida; si arranca con él, no carga las rutas (los hosts responden `404`). El health público del workflow lo detecta.
- Diagnóstico en el servidor: `docker logs traefik` (errores del file provider y de ACME), `docker inspect traefik --format '{{.Config.Image}}'` (identidad en ejecución) y `cat /opt/piedrazul/traefik/dynamic/routes.yml`.

La imagen es un tercero fijado con el mismo contrato que Keycloak: `infra/compose/traefik.yml` declara `traefik:<versión>@sha256:<índice OCI>`; el workflow la resuelve una vez a su manifiesto `linux/amd64`, la escanea y la pasa a converge (`traefik_image_ref`, `traefik_image_index_digest`); `traefik_image.yml` exige que corresponda a la declaración, la descarga por digest y comprueba plataforma y versión (`org.opencontainers.image.version` igual al tag); `image.env` la recibe como `TRAEFIK_IMAGE` y `prod.yml` la exige (`${TRAEFIK_IMAGE:?}`). Deploy conserva la `TRAEFIK_IMAGE` de `image.env` (si falta o está malformada falla antes de mutar: corre converge). Tras converge, `verify_running_images.yml` comprueba que el contenedor `traefik` ejecuta esa identidad. Para actualizar Traefik: revisar notas de migración y avisos de seguridad, reemplazar tag y digest del índice en `traefik.yml` (`docker buildx imagetools inspect traefik:<versión>`) y desplegar con converge.

#### Identidad de las imágenes propias

Backend y postgres se despliegan siempre por identidad exacta: `image.env` contiene `BACKEND_IMAGE` y `POSTGRES_IMAGE` como `repo@sha256:<manifiesto linux/amd64>`, y Compose las exige (`${BACKEND_IMAGE:?}` en `backend.yml`, `${POSTGRES_IMAGE:?}` en `prod.yml`). Ansible no selecciona ni resuelve tags: `release_images.yml` valida el formato contra `backend_image` / `postgres_image` y descarga por digest; sin identidad exacta válida falla antes de tocar `image.env`.

El servidor guarda dos estados distintos:

| Archivo | Contenido | Quién lo escribe |
|---------|-----------|------------------|
| `image.env` | Entrada de Compose; puede contener un candidato aún no verificado o fallido | `setup.yml` y `deploy.yml`, antes de levantar servicios |
| `last-success.env` (`release_state_file`) | Último despliegue **exitoso**: `BACKEND_IMAGE_REF`, `POSTGRES_IMAGE_REF` (exactas), sus digests de índice y tags (metadato), `RELEASE_COMMIT` y `RELEASE_RUN` | solo `record_release.yml` |

`record_release.yml` corre como último paso del workflow, después de que `deploy.yml` terminó, de que `verify-release.yml` confirmó identidad en ejecución y health de los contenedores, y de que pasaron los health checks públicos. Antes de escribir vuelve a verificar identidad y health. La escritura es atómica.

Identidad en ejecución (`verify_running_images.yml`): el contenedor existe y corre, su `Config.Image` es la referencia exacta esperada (un contenedor creado desde un tag no cuenta, aunque el contenido coincida) y la imagen local que ejecuta está registrada por el daemon bajo ese digest (`RepoDigests`). El health (`verify_health.yml`) se verifica por separado: un backend `healthy` no prueba que corra el contenido esperado. Ambas tareas leen los contenedores sin mostrarlos (`no_log`) y verifican sobre un resumen sin el entorno: la salida nunca incluye las variables de entorno de los contenedores (contraseñas de la base, secretos de clientes). Sirven para `postgres`, `backend` y `keycloak`; una identidad esperada vacía o por tag falla.

Si cualquier paso falla o el run se cancela antes del registro, `last-success.env` no cambia: el siguiente reuso vuelve al último éxito, aunque `image.env` o el contenedor tengan el candidato fallido. No hay rollback automático del contenedor; para eso existe el rollback explícito del backend. Un primer deploy fallido en un servidor nuevo no deja estado reusable.

`last-success.env` es la única fuente de reuso y la lee el workflow, nunca Ansible (ver [Cadena de producción](#cadena-de-producción-workflow)). Keycloak no forma parte de `last-success.env`: su identidad sale del commit, no del reuso (ver abajo). El formato anterior (`backend-deployed.env`, solo tag) no se migra: un servidor sin `last-success.env` necesita un deploy con build de backend y de postgres (o rollback de backend más build de postgres); un reuso falla cerrado.

#### Identidad de Keycloak (`keycloak_image.yml`)

Keycloak es un tercero fijado: `infra/compose/keycloak.yml` declara `quay.io/keycloak/keycloak:<versión>@sha256:<índice OCI>` (hoy `26.8.1@sha256:79c623a2…`). El digest del índice es la identidad; el tag solo la hace legible. Desarrollo (`docker-compose.yml`, también `keycloak-dev-secret`) usa esa referencia tal cual y Docker elige el hijo de su plataforma. Producción nunca despliega el índice ni un tag: `prod.yml` reemplaza la imagen por `${KEYCLOAK_IMAGE:?}` de `image.env`, el manifiesto `linux/amd64` exacto de ese índice.

- El workflow (`image_targets`, sin credenciales de producción) lee la referencia declarada, exige el formato fijado, la resuelve **una vez y por digest** con `resolve-amd64-image.sh` (el tag nunca se consulta en el registry) y pasa `keycloak_image_ref` (`quay.io/keycloak/keycloak@sha256:<linux/amd64>`) y `keycloak_image_index_digest`. Esa misma referencia se escanea y la recibe converge. Sin referencia fijada o sin identidad resoluble el job falla y producción no empieza.
- `keycloak_image.yml` (desde `setup.yml`, antes de escribir `image.env`): el índice recibido debe ser el declarado en el `keycloak.yml` que este converge copia, la referencia debe ser un manifiesto (no el índice), se descarga por digest para `linux/amd64` y la etiqueta `org.opencontainers.image.version` de la imagen descargada debe coincidir con el tag declarado. Cualquier diferencia falla antes de tocar `image.env`.
- Solo `setup.yml` (converge) escribe `KEYCLOAK_IMAGE`, y solo `keycloak_lifecycle.yml` arranca, recrea o actualiza Keycloak. `deploy.yml` nunca lo hace: antes de cualquier mutación exige exactamente una `KEYCLOAK_IMAGE` exacta en `image.env` y el contenedor `keycloak` existente, en ejecución, `healthy` y creado desde esa misma referencia; levanta postgres y backend **sin dependencias** (el `depends_on` del backend no puede arrancar ni recrear Keycloak) y al final verifica que `keycloak` es el mismo contenedor (Id e inicio). Ante `KEYCLOAK_IMAGE` ausente (también un `image.env` anterior a este contrato), duplicada o malformada, Keycloak ausente, detenido o no `healthy`, o un `image.env` que pide otra identidad que la que corre (p. ej. un converge interrumpido después de escribirlo), deploy falla sin escribir `image.env` ni tocar servicios: corre converge.
- La identidad local que ejecuta el contenedor se comprueba por `Config.Image` y `RepoDigests` (como backend y postgres), nunca comparando el Id local con el índice o el manifiesto: según el almacén de imágenes del daemon, el Id es el digest de la configuración o del manifiesto.

**Actualizar Keycloak** es cambiar la referencia fijada en `keycloak.yml` (y `docker-compose.yml`) por la nueva `<versión>@sha256:<índice>` y desplegar por la cadena normal: el cambio en `infra/compose` corre converge, que recrea Keycloak con la imagen nueva; Keycloak migra su base al arrancar. Esa migración es **solo hacia adelante**: volver a una imagen anterior no deshace el esquema ni los datos migrados y no es una vía de recuperación (ver [`recuperar-keycloak.md`](../../docs/operations/runbooks/recuperar-keycloak.md#actualización-de-la-versión-de-keycloak)).

#### Ciclo técnico de Keycloak (`keycloak_lifecycle.yml`)

Corre en cada `converge`, después de `setup.yml` (base reconciliada, `.env` vigente) y antes de `refresh_backend.yml`. Decide por el **estado de Keycloak**, no por archivos del host: no hay marker (el histórico `/opt/piedrazul/.keycloak_initialized` se borra si existe y no se consulta).

**Clasificación** (`keycloak_state.yml`):

1. Lee la base de Keycloak por el socket local de postgres (`files/keycloak-state.sql`, solo lectura, nunca columnas con secretos ni nombres de usuarios humanos): estado del schema `keycloak` (ausente, **vacío** —como lo deja `01-init-databases.sh`: cero tablas, secuencias, funciones y tipos—, **parcial** —objetos sin tabla `realm`— o inicializado), los realms `master` y `piedrazul`, autoridades temporales (`is_temporary_admin=true`, la marca de Keycloak para lo creado por `bootstrap-admin` o `KC_BOOTSTRAP_ADMIN_*`) clasificadas como **propias** (cliente con clientId exacto `kc_temp_admin_owned_regex` y solo los roles que da bootstrap-admin) o **ajenas** (todo lo demás), forma (banderas) y roles directos de los service accounts de `piedrazul-keycloak-automation` y `piedrazul-backend`, y el **número** de usuarios humanos con rol `admin` en `master`.
2. Si no hay divergencias, prueba la credencial deseada de automatización contra la API (`files/keycloak-admin.sh probe`).

| Estado | Condición | Resultado |
|--------|-----------|-----------|
| **P2** nuevo | el schema `keycloak` existe y está vacío | evento con autoridad temporal (con importación del realm y creación del administrador humano permanente) → reclasifica (debe ser P1) → reconciliación P1 |
| **P1** conocido/reconciliado | sin autoridad temporal; automatización y backend con la forma y los roles exactos (backend: los del realm del repositorio; automatización: `kc_automation_roles`); la automatización autentica con `KC_AUTOMATION_CLIENT_SECRET` y ve el backend | reconciliación P1, sin autoridad temporal |
| **P3 bloqueante** | schema ausente o parcial; falta `master`, `piedrazul` o el cliente `piedrazul-backend`; autoridad temporal ajena | **falla cerrado antes de arrancar Keycloak**, también con `keycloak_recovery=true`: puede implicar pérdida de identidad o autoridad desconocida → escalar |
| **P3 recuperable** | credencial de automatización rechazada; falta la automatización; forma o roles técnicos distintos; autoridad temporal propia huérfana | **falla cerrado** con los motivos; no adopta, reinicia ni borra nada. Solo la recuperación autorizada lo corrige |
| sin respuesta | Keycloak no respondió a la prueba | falla como transitoria (no es P3); reintentar |

Si el schema está vacío pero el contenedor `keycloak` corre (p. ej. reiniciando sin conectar), se detiene antes del evento y se vuelve a leer; si entre tanto escribió en la base, ya no es P2.

**Imagen y versión de la base**, antes de arrancar o tocar Keycloak (servicio o contenedor de un solo uso del evento):

- `keycloak_image_ref` es obligatoria y exacta, está descargada y declara su versión, y la configuración de Compose arrancaría exactamente esa referencia (`KEYCLOAK_IMAGE` de `image.env`). Si no, falla sin clasificar ni arrancar nada.
- `keycloak-state.sql` también lee `migration_version`: la versión que Keycloak toma como almacenada, la fila de `migration_model` con el `update_time` más reciente (Keycloak registra una por versión; `VERSION` y `UPDATE_TIME` únicos desde su changelog 26.2.6). Con la base inicializada y sin versión conocida —tabla ausente, sin filas, versión nula, más de una fila más reciente o un valor sin forma `X.Y.Z`— falla cerrado antes de arrancar o tocar Keycloak, también con `keycloak_recovery=true`: no se adivina ni se repara. Si la versión es **posterior** a la versión de la imagen, falla cerrado (también con `keycloak_recovery=true`) sin detener, recrear ni arrancar Keycloak: volver atrás sobre una base migrada no está soportado. Si es anterior, informa `base migrada por X, arrancar Y la migra hacia adelante (sin vuelta atrás)`; si es igual, `sin migración`.
- Al final del ciclo verifica que el contenedor `keycloak` corre exactamente `keycloak_image_ref` (`verify_running_images.yml`) y que la base quedó migrada a la versión de la imagen; si no, falla sin informar éxito.

Una actualización de versión sobre una base P1 no es un evento de identidad: `Levantar Keycloak sobre la base ya inicializada` recrea el contenedor con la imagen nueva (Keycloak migra la base al arrancar, dentro de `kc_start_timeout`) y sigue la clasificación normal. Si Keycloak no arranca o no migra, converge falla en ese paso y el workflow no llega a la verificación ni al registro; la convergencia siguiente vuelve a clasificar por la base.

Keycloak de producción arranca con `start`, **sin `--import-realm`**: un reinicio nunca recrea un realm que falta. El realm del repositorio solo se importa dentro de un evento P2.

**Reconciliación P1** (`keycloak-admin.sh reconcile`, con la automatización): sin leer el secreto vigente, prueba si el backend ya autentica con `KC_BACKEND_CLIENT_SECRET`; si Keycloak lo rechaza, lo fija. Después verifica el consumidor real: el backend autentica con ese secreto y lee su realm por la API de administración. Repetible: sin cambios no escribe nada.

**Evento con autoridad temporal** (`keycloak_bootstrap.yml`, P2 o recuperación P3 autorizada):

1. Detiene Keycloak: `kc.sh bootstrap-admin` exige todos los nodos detenidos (no se depende de que Keycloak lo verifique por sí mismo).
2. `docker compose run --rm --no-deps keycloak bootstrap-admin service` con la configuración del servicio: cliente admin temporal en `master` con id `piedrazul-tmp-admin-<fecha>-<aleatorio>` y secreto aleatorio generado en el runner. No se persiste en el host ni va en argv: viaja por entorno, en tránsito, al contenedor de un solo uso (`--rm`) y al exec de kcadm (sesión en `/dev/shm`, borrada al terminar). En una base nueva el mismo comando crea el esquema y el realm `master`.
3. Solo P2: `docker compose run --rm --no-deps keycloak import --dir … --override false` importa el realm del repositorio (el cliente backend no trae secreto y Keycloak genera uno aleatorio). Una recuperación nunca importa ni recrea `master`, `piedrazul` ni `piedrazul-backend`; sí puede crear o corregir el cliente técnico `piedrazul-keycloak-automation` (paso 5).
4. Arranca Keycloak (`start`).
5. Con la autoridad temporal (`keycloak-admin.sh establish`): crea o corrige `piedrazul-keycloak-automation` (confidencial, solo service account, secreto `KC_AUTOMATION_CLIENT_SECRET`, roles exactos: agrega los que faltan y quita los demás), y forma y roles exactos de `piedrazul-backend`. Comprueba que la automatización autentica. Si falla, el mensaje muestra el avance (`automation.*`, `backend.*`, `role.*`) y el error.
   Solo P2, a continuación (`keycloak-admin.sh platform-admin`): crea el administrador humano permanente de `master` (ver [Administrador humano permanente](#administrador-humano-permanente)). Si falla, el evento falla con el avance (`platform_admin.*`) y el error; el estado técnico ya quedó establecido y el paso 6 se ejecuta igual.
6. Siempre, también si 1-5 fallaron (`always`): elimina la autoridad temporal **propia** —la del evento, al final, y huérfanas propias de eventos interrumpidos—, verifica que Keycloak rechaza la del evento y que la base no tiene ninguna propia. Nunca toca una autoridad temporal ajena (de todos modos bloquea antes del evento). Después la clasificación debe dar P1 y sigue la reconciliación P1.

Si el evento falla, `converge` falla con la tarea y el motivo. Si además la autoridad temporal no se pudo eliminar (p. ej. Keycloak no arrancó), el mensaje lo dice: su secreto nunca se persistió (solo estuvo en tránsito durante ese run), y la convergencia siguiente la ve como huérfana propia (P3 recuperable) → recuperación. No se borra el realm ni identidades fuera de la autoridad temporal propia.

**Recuperación P3**: solo con `-e keycloak_recovery=true`, que el workflow pasa únicamente desde `workflow_dispatch` con `keycloak_recovery=true` y `run_host_config=true` (mismo job `production`, misma aprobación del environment y misma concurrencia). Solo para P3 recuperable: con divergencias bloqueantes falla igual, sin crear autoridad. Con estado P1 o P2 la marca no crea autoridad temporal adicional. Procedimiento: [`docs/operations/runbooks/recuperar-keycloak.md`](../../docs/operations/runbooks/recuperar-keycloak.md).

**Autoridades técnicas**

| Principal | Consumidor | Dónde vive el secreto | Ciclo de vida |
|-----------|------------|-----------------------|---------------|
| `piedrazul-backend` (service account del realm `piedrazul`) | backend en ejecución (Keycloak Admin Client) | GitHub `KC_BACKEND_CLIENT_SECRET` → runner → `.env` del host → solo el servicio `backend` | durable; rotación: cambiar el secreto y correr converge (P1 lo fija y verifica, `refresh_backend.yml` recrea el backend) |
| `piedrazul-keycloak-automation` (service account en `master`, roles `manage-clients` y `view-clients` de `piedrazul-realm`) | `keycloak_lifecycle.yml` | GitHub `KC_AUTOMATION_CLIENT_SECRET` → runner → en tránsito al exec de kcadm dentro del contenedor `keycloak` (entorno del exec y sesión en `/dev/shm`, borrada al terminar); nunca persistido en `.env` ni en Compose | durable; no puede modificarse a sí misma: tras rotar el secreto en GitHub, converge da P3 (credencial rechazada) y la recuperación autorizada fija el nuevo |
| `piedrazul-tmp-admin-*` (admin temporal de `master`) | un evento P2 o de recuperación | generado en el runner; en tránsito al contenedor de un solo uso de bootstrap-admin y al exec de kcadm; nunca persistido | creado y eliminado dentro del evento |

##### Administrador humano permanente

Una instalación nueva (P2) crea **una** cuenta humana permanente en `master`, `kc_platform_admin_username` (`piedrazul-platform-admin`), con el rol de realm `kc_platform_admin_role` (`admin`): la autoridad completa de la plataforma para administración manual excepcional. No es un principal del ciclo técnico: ni la automatización, ni CI, ni el backend autentican con ella.

- **Creación, solo en el evento P2**: falla ante un usuario existente con ese nombre (no lo adopta); fija la política de contraseñas de `master` `kc_master_password_policy` (`length(16)`: mínimo de 16, sin categorías ni rotación, hash por defecto) si `master` no tiene ninguna y falla sin pisarla si tiene otra; la crea deshabilitada con `CONFIGURE_TOTP` y `UPDATE_PASSWORD`, fija la contraseña inicial como temporal y el rol, la lee de vuelta y recién entonces la habilita; comprueba que la contraseña llegó intacta y que sola no da acceso. Una creación interrumpida deja la cuenta deshabilitada.
- **Contraseña inicial**: `KC_PLATFORM_ADMIN_INITIAL_PASSWORD`, secreto de un solo uso del environment. La convergencia la valida (presente, ≥ 16 caracteres, sin saltos de línea ni espacios en blanco en los extremos —caracteres especiales y espacios intermedios sí—, distinta de los valores actuales de `KC_BACKEND_CLIENT_SECRET` y `KC_AUTOMATION_CLIENT_SECRET` y de los valores de referencia `*SECRET`/`*PASSWORD`/`*KEY` de `.env.example`, sin el nombre de la cuenta) **solo si el estado es P2 y antes del evento**: si falla, la base de Keycloak queda vacía. Las mismas reglas corren antes, en el runner (`playbooks/keycloak-admin-preflight.yml`), cuando el saved plan revisado crea o reemplaza el servidor: si fallan, el workflow no solicita el Apply. Fuera de P2 no se exige ni se usa (si sigue presente, la convergencia avisa que se elimine). Viaja como las credenciales técnicas: runner → en tránsito al exec de kcadm, en JSON armado por Ansible; nunca en `.env`, Compose, argv ni la salida.
- **Después**: contraseña y OTP son del operador. Ningún P1, rotación ni recuperación P3 la modifica, repara o recrea, ni reaplica la política de `master` (que es de realm: vale para toda contraseña nueva de `master`, no para clientes técnicos ni para `piedrazul`).
- **Estado** (aparte de P1/P2/P3, solo informativo): `files/keycloak-state.sql` lee, filtrando por el nombre sin seleccionarlo, si está habilitada, si es un usuario local, si tiene el rol, sus acciones requeridas y cuántas credenciales `password` y `otp` tiene, además de la política de contraseñas de `master`. Cada convergencia lo resume en `ADMINISTRACIÓN HUMANA DE KEYCLOAK (aparte del estado técnico): …` como `FALTA`, `INESPERADA`, `INCOMPLETA`, `CREADA, primer login pendiente`, `SIN OTP` o `CONFIGURADA`. Ninguno hace fallar la convergencia ni es requisito del backend.
- **Segundo factor**: OTP obligatorio en el primer login; después, con los flujos por defecto de `master` (no modificados), Keycloak lo exige mientras la cuenta tenga uno enrolado. Retirarlo es decisión del operador; entonces la contraseña sola da acceso (estado `SIN OTP`, informativo, no se restituye).

Primer login, custodia, verificación del OTP, estados y asignación manual de roles de aplicación: [`docs/operations/runbooks/administrador-keycloak.md`](../../docs/operations/runbooks/administrador-keycloak.md).

La clasificación además informa **cuántos** usuarios humanos de `master` tienen rol `admin` (el administrador permanente incluido; p. ej. también un `kc-admin` de la automatización anterior en un servidor viejo), sin nombres, sin usarlos ni borrarlos.

**Desarrollo local**: `docker-compose.yml` (raíz) agrega `KC_BOOTSTRAP_ADMIN_*` solo a su Keycloak de desarrollo, y el servicio de un solo uso `keycloak-dev-secret` fija en el realm local el `KC_BACKEND_CLIENT_SECRET` del `.env`.

**Pruebas**: `infra/tests/keycloak-lifecycle-check.sh` (contrato estático: realm, `.env`, Compose, workflow, Ansible, desarrollo), `infra/tests/image-identity-check.sh` (resolución de referencias fijadas y contrato escaneado = desplegado de Keycloak, sin red) e `infra/tests/keycloak-lifecycle-harness.sh` (identidad de Keycloak inválida o distinta antes y después de arrancar; con `HARNESS_TARGET=upgrade`, P2 en la versión anterior y actualización real por converge con la base migrada, administrador humano con OTP e identidades pendientes preservados y vuelta atrás rechazada, seguidos de todos los escenarios sobre la base migrada; P1/P2, rotaciones con caracteres especiales, P3 recuperable y su recuperación —también interrumpida o fallida a mitad—, P3 bloqueante con y sin recuperación: autoridad temporal ajena, realm ausente, esquema parcial, base inicializada fuera del ciclo; y el administrador humano: contraseña inicial exigida antes del evento, creación, primer login de navegador con OTP —`infra/tests/keycloak-browser-login.py`—, logins siguientes, colisión, OTP eliminado, borrado y creación interrumpida; con estos mismos task files contra PostgreSQL y Keycloak desechables).

#### `deploy.yml`

Rollout de las imágenes propias. Pasos:

1. Verifica que `.env` existe y que `KC_BACKEND_CLIENT_SECRET` no está vacío
2. Valida y descarga por digest `backend_image_ref` y `postgres_image_ref` (`release_images.yml`)
3. Antes de mutar nada: `image.env` tiene exactamente una `KEYCLOAK_IMAGE` exacta y `keycloak` existe, corre `healthy` y fue creado desde ella (si no, falla: corre converge)
4. Escribe `image.env` con esas referencias exactas y la misma `KEYCLOAK_IMAGE`
5. `docker_compose_v2` de `postgres` y `backend` sin dependencias (`pull: missing`: las imágenes ya están por digest)
6. Verifica que `keycloak` es el mismo contenedor, sin reinicio

La verificación y el registro del último éxito son pasos posteriores del workflow (`verify-release.yml`, `record-release.yml`).

#### Base de datos: autoridades, reconciliación y migración

Cuatro autoridades separadas sobre la base `piedrazul_db`. Los nombres están en `group_vars/all.yml`; las contraseñas son secretos del GitHub Environment `production-hetzner` (no secretos de repositorio), llegan al paso de converge y quedan en `.env` del servidor.

| Autoridad | Variables | Rol (`group_vars`) | Consumidores | Alcance |
|-----------|-----------|--------------------|--------------|---------|
| Administrativa | `POSTGRES_USER`, `POSTGRES_PASSWORD` | `postgres_user` | solo el contenedor `postgres` (inicialización y reconciliación) | superusuario |
| Aplicación | `APP_DB_USERNAME`, `APP_DB_PASSWORD` | `app_db_username` | `backend` en ejecución | DML sobre las tablas de la aplicación en `piedrazul`; sin `CREATE`; ningún privilegio sobre `flyway_schema_history` |
| Migraciones | `MIGRATION_DB_USERNAME`, `MIGRATION_DB_PASSWORD` | `migration_db_username` | solo el servicio `migrate` | dueño de `piedrazul`, `extensions` y del historial de Flyway |
| Keycloak | `KC_DB_USERNAME`, `KC_DB_PASSWORD` | `kc_db_username` | `keycloak` | dueño de `keycloak` |

`postgres` recibe todas porque las fija. `migrate` recibe además `APP_DB_USERNAME` (solo el nombre, para el placeholder `${app_role}` de las migraciones). Compose exige cada variable de la base (`${VAR:?}`): ausente o vacía, el render falla. El backend corre siempre con `SPRING_FLYWAY_ENABLED=false` y `JPA_DDL_AUTO=validate`, fijos en `compose/backend.yml` y no configurables desde `.env`: no evoluciona ni repara el esquema; si no coincide con las entidades, no arranca.

**Convergencia (`db_converge.yml`, en `setup.yml` y en `deploy.yml`)**

1. `postgres` se (re)crea si cambió su imagen o su entorno y se espera a que esté healthy. `PGDATA` se conserva.
2. Se vuelve a correr `01-init-databases.sh` dentro del contenedor (`docker exec -u postgres`), el mismo script que inicializa un `PGDATA` vacío. Es idempotente: crea lo que falte, fija las contraseñas declaradas de los cuatro roles, los schemas, sus dueños, los permisos y los default privileges. Se conecta por el socket local, así que no necesita las contraseñas anteriores; las anteriores dejan de autenticar.

**Historial de Flyway**: `flyway_schema_history` lo crea `migration_role` y solo él lo modifica. Los default privileges que dan DML a `app_role` sobre las tablas nuevas de `piedrazul` también lo alcanzarían, así que se retira en dos puntos idempotentes: al final de cada migración (callback de Flyway `ProtectSchemaHistoryCallback`, solo en `SchemaMigrationApplication`) y en cada reconciliación si la tabla ya existe (`REVOKE ALL … FROM app_role` en `01-init-databases.sh`). Las default privileges no se aplican a tablas existentes, así que una reconciliación posterior no lo deshace; un deploy sin migración (rollback, reuso) también pasa por la reconciliación.

Solo después arrancan o se recrean los consumidores: Keycloak (`setup.yml`), `migrate` y `backend` (`deploy.yml`), y en un converge sin deploy el backend que ya corre (`refresh_backend.yml`, al final de `converge.yml`, después de la sincronización de Keycloak). `refresh_backend.yml` solo actúa si el contenedor `backend` corre exactamente `backend_image_ref`; nunca introduce una imagen nueva.

**Rotación de credenciales de la base**: actualizar el secreto en el GitHub Environment `production-hetzner` y correr el workflow con `run_host_config=true` (workflow_dispatch). Converge reescribe `.env`, recrea `postgres` con las credenciales nuevas, las reconcilia y recrea Keycloak y el backend en ejecución. No hace falta borrar `PGDATA`, conocer las contraseñas anteriores ni recrear el servidor. No es atómica: entre la reconciliación y la recreación de cada consumidor, ese consumidor puede fallar conexiones nuevas durante un intervalo corto. En un run con converge y deploy a la vez, el backend se recrea al final del deploy.

**Migración (`migrate.yml`, en `deploy.yml` con `app_migrate=true`)**

- Servicio `migrate` de `compose/migrate.yml`: misma imagen que `backend` (`BACKEND_IMAGE` de `image.env`), punto de entrada `SchemaMigrationApplication` (vía `PropertiesLauncher`). Solo carga Flyway con la configuración `spring.flyway.*` de `application.yaml`: sin servidor web, JPA, seeders, schedulers ni Keycloak. Aplica las migraciones versionadas y repetibles que trae la imagen y termina.
- Perfil `migrate`: `docker compose up` nunca lo arranca. Ansible lo corre con `docker compose --profile migrate run --rm --no-deps migrate`, después de verificar en la configuración efectiva de Compose que `migrate` y `backend` resuelven a `backend_image_ref`.
- Repetirla con el esquema al día es seguro (Flyway no aplica nada).
- **Exclusividad**: la reconciliación y la migración corren bajo `flock` sobre `db_lock_file` (`/opt/piedrazul/db.lock`), con espera máxima `db_lock_timeout` (600 s). El lock se libera al terminar el proceso, también si falla.
- **Fallo**: si la migración termina con error, `deploy.yml` falla con `MIGRACIÓN: falló …` antes de arrancar el backend candidato: no hay verificación de identidad o health, ni registro del último éxito. No se ejecuta `flyway repair` ni ninguna corrección automática. El contenedor de migración se elimina (`--rm`); un reintento vuelve a correr la migración sobre el estado que dejó PostgreSQL, sin limpieza previa.

**Cuándo migra el workflow**: `app_migrate=true` solo si el backend seleccionado es un build de este run. Un rollback explícito (`image_tag`) no migra; tampoco el reuso del último éxito, que ya se desplegó con éxito (o es un rollback registrado). En esos casos el backend valida el esquema existente al arrancar y, si no coincide, el health falla y el último éxito no avanza.

**Verificación local** (credenciales sintéticas, sin red ni servidor):

```bash
infra/tests/db-contract-check.sh    # env.j2 → Compose → orden de Ansible y del workflow
infra/tests/db-migration-check.sh   # PostgreSQL desechable: desde cero, repetición, autoridades, rotación, fallos
```

`db-migration-check.sh` construye las imágenes de `backend/` e `infra/postgres/` (o usa `BACKEND_IMAGE` / `POSTGRES_IMAGE`) y no depende del contenido ni del número de migraciones. Usa los nombres fijos de producción (`postgres`, `piedrazul_net`) en su propio proyecto y no corre si ya existen fuera de él.

#### Handler: `Restart app stack`

Se dispara cuando cambian los compose files o el `.env`. Hace `docker_compose_v2 state: present` — Compose reconcilia solo lo que cambió.

---

## Template `.env` (`env.j2`)

El archivo `.env` se genera en cada converge desde este template. Las variables vienen de tres fuentes:

| Origen | Ejemplos |
|--------|----------|
| `group_vars/all.yml` | `compose_project_name`, `kc_realm`, `kc_port` |
| `host_vars/vps.yml` | `kc_hostname`, `api_public_domain`, `acme_email` |
| Variables de entorno del runner (secretos de GitHub) | `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `MIGRATION_DB_PASSWORD`, `KC_DB_PASSWORD`, `KC_BACKEND_CLIENT_SECRET` (vía `app_kc_backend_secret`), `CLOUDFLARE_DNS_API_TOKEN` |

SendGrid está deshabilitado (`notifications_sendgrid_enabled: false`): `.env` lleva
`NOTIFICATIONS_SENDGRID_ENABLED=false` y `SENDGRID_API_KEY` vacía, y el converge no recibe
`PIEDRAZUL_BACKEND_MAIL_SEND`. Con el proveedor habilitado, `env.j2` escribe esa variable del
runner como `SENDGRID_API_KEY` y `setup.yml` falla antes de generar `.env` si está vacía.

`.env` no lleva credenciales de administración de Keycloak ni `KC_AUTOMATION_CLIENT_SECRET`: el secreto de automatización y el de la autoridad temporal de un evento no se persisten en el host; Ansible solo los entrega en tránsito al exec administrativo de Keycloak (ver [Ciclo técnico de Keycloak](#ciclo-técnico-de-keycloak-keycloak_lifecycleyml)).

---

## Secrets requeridos en GitHub

Los siguientes secrets pertenecen al GitHub Environment `production-hetzner` (alcance declarado en `.github/scripts/audit-github-trust.sh`), no al repositorio. Solo el job `production` los lee:

```
POSTGRES_PASSWORD            # Password del superusuario administrativo de postgres
APP_DB_PASSWORD              # Password del rol de la aplicación (backend en ejecución)
MIGRATION_DB_PASSWORD        # Password del rol de migraciones (solo el servicio migrate)
KC_DB_PASSWORD               # Password del usuario de Keycloak en postgres
KC_BACKEND_CLIENT_SECRET     # Secreto del cliente piedrazul-backend (backend en ejecución)
KC_AUTOMATION_CLIENT_SECRET  # Secreto de piedrazul-keycloak-automation (no se persiste en el host)
KC_PLATFORM_ADMIN_INITIAL_PASSWORD  # Un solo uso: contraseña inicial del administrador humano de
                                    # Keycloak; solo la exige una instalación nueva (P2). Se elimina
                                    # tras el primer login (docs/operations/runbooks/administrador-keycloak.md)
CLOUDFLARE_DNS_API_TOKEN     # Token de Cloudflare para DNS challenge de Let's Encrypt
ANSIBLE_SSH_KEY              # Clave privada SSH para que Ansible se conecte al servidor
IDENTITY_SEED_ADMIN_PASSWORD # Un solo uso: contraseña temporal del primer ADMIN de negocio. Solo
                             # se lee con la variable IDENTITY_SEED_ENABLED=true; se elimina tras
                             # el primer login (docs/operations/runbooks/primer-admin-negocio.md)
```

Qué exige la verificación de credenciales depende del modo del run: con configuración del
servidor (`run_host_config`) exige las contraseñas de la base, `CLOUDFLARE_DNS_API_TOKEN`,
`KC_BACKEND_CLIENT_SECRET` y `KC_AUTOMATION_CLIENT_SECRET`, que solo recibe el converge. Un
deploy sin configuración del servidor no las recibe ni las exige (solo `TF_API_TOKEN` y
`ANSIBLE_SSH_KEY`): usa el `.env` que dejó el converge, y `deploy.yml` falla antes de mutar si
falta o le falta una clave de la base o `KC_BACKEND_CLIENT_SECRET` ("corre converge primero").

Bootstrap del primer `ADMIN` de negocio (variables `IDENTITY_SEED_*` del environment): con
`IDENTITY_SEED_ENABLED=true`, `identity_seed_inputs.yml` valida las entradas antes de escribir
`.env`, `identity_seed_delivery.yml` comprueba que Compose las entregaría intactas e
`identity_seed_runtime.yml` (al final de `refresh_backend.yml` y de `deploy.yml`) exige que el
backend en ejecución tenga en vigor la configuración del `.env` (sus `IDENTITY_SEED_*` son los
que Compose entregaría) y que informe `CREATED` o `ALREADY_LINKED_READY`. El backend es
fail-closed: con otro resultado no arranca. Deshabilitado, `.env` lleva las entradas vacías y
esas mismas tareas comprueban que ni Compose ni el contenedor recreado conservan la contraseña.
Un converge que no recrea el backend lo deja al deploy del mismo run (`app_deploy_follows`) o
falla si el cambio queda pendiente; un deploy falla si el `.env` no corresponde a la decisión
del environment (`app_identity_seed_intended`). Con el bootstrap habilitado, `identity_seed_image_gate.yml` rechaza una imagen
del backend sin la etiqueta `org.piedrazul.identity-bootstrap=create-only-v1` antes de escribir
`.env` (setup) y antes de migrar o arrancar (deploy).
Procedimiento: [`primer-admin-negocio.md`](../../docs/operations/runbooks/primer-admin-negocio.md).

El workflow los inyecta como variables de entorno antes de correr Ansible:

```yaml
- name: Run Ansible converge
  env:
    ANSIBLE_HOST_KEY_CHECKING: "False"
    POSTGRES_PASSWORD: ${{ secrets.POSTGRES_PASSWORD }}
    APP_DB_PASSWORD: ${{ secrets.APP_DB_PASSWORD }}
    MIGRATION_DB_PASSWORD: ${{ secrets.MIGRATION_DB_PASSWORD }}
    KC_DB_PASSWORD: ${{ secrets.KC_DB_PASSWORD }}
    KC_BACKEND_CLIENT_SECRET: ${{ secrets.KC_BACKEND_CLIENT_SECRET }}
    KC_AUTOMATION_CLIENT_SECRET: ${{ secrets.KC_AUTOMATION_CLIENT_SECRET }}
    CLOUDFLARE_DNS_API_TOKEN: ${{ secrets.CLOUDFLARE_DNS_API_TOKEN }}
  run: |
    ansible-playbook \
      -i infra/ansible/inventories/prod \
      -i "$RUNNER_TEMP/hosts.runtime.yml" \
      infra/ansible/playbooks/converge.yml
```

---

## Cómo agregar nuevas variables

### Caso 1: Variable no sensible igual en todos los entornos

**Ejemplo:** agregar soporte para Redis con una URL fija.

1. Agregar a `group_vars/all.yml`:

```yaml
redis_port: 6379
```

2. Agregar al template `env.j2`:

```jinja
# Redis
REDIS_PORT={{ redis_port }}
```

3. Agregar al compose file que lo necesite (`backend.yml` por ejemplo):

```yaml
environment:
  REDIS_PORT: ${REDIS_PORT}
```

### Caso 2: Variable no sensible diferente por entorno

**Ejemplo:** dominio de Traefik dashboard distinto por entorno.

1. Agregar a `host_vars/vps.yml`:

```yaml
traefik_domain: traefik.piedrazul.narvaezlab.dev
```

2. Agregar a `host_vars/vps_oci.yml`:

```yaml
traefik_domain: traefik.piedrazul-oci.narvaezlab.dev
```

3. Agregar al template `env.j2`:

```jinja
# Traefik
TRAEFIK_DOMAIN={{ traefik_domain }}
```

### Caso 3: Variable sensible (secret)

**Ejemplo:** agregar credenciales de RabbitMQ.

1. Crear el secret en GitHub:
   - `Settings → Secrets → Actions → New repository secret`
   - Nombre: `RABBITMQ_PASSWORD`

2. Agregar al template `env.j2`:

```jinja
# RabbitMQ
RABBITMQ_PASSWORD={{ lookup('env', 'RABBITMQ_PASSWORD') }}
```

3. Agregar al paso de Ansible en el job `production` de `.github/workflows/production-hetzner.yml`:

```yaml
env:
  RABBITMQ_PASSWORD: ${{ secrets.RABBITMQ_PASSWORD }}
```

4. Agregar al compose file que lo necesite.

### Caso 4: Secret gestionado solo en el servidor

Para secrets que no deben pasar por GitHub en ningún momento:

1. En `setup.yml`, leer el valor existente del `.env` o generarlo con `openssl` / `ansible.builtin.command`
2. Guardarlo como `set_fact` con prefijo del rol (`app_`)
3. Usarlo en `env.j2` como variable Jinja normal

---

## Convenciones de naming

Ansible-lint (profile `production`) exige que las variables definidas dentro de un rol lleven el nombre del rol como prefijo para evitar colisiones.

| Rol | Prefijo requerido | Ejemplos |
|-----|-------------------|---------|
| `app` | `app_` | `app_kc_backend_secret`, `app_env_file`, `app_kc_state` |
| `docker_host` | `docker_host_` | `docker_host_apt_arch` |
| `common` | `common_` | — |
| `hardening` | `hardening_` | — |

Las variables de inventario (`group_vars`, `host_vars`) no necesitan prefijo porque no son internas de un rol.

---

## Verificación local antes de subir

```bash
cd infra/ansible

# Instalar colecciones
ansible-galaxy collection install -r requirements.yml

# Lint completo (profile production)
ansible-lint playbooks roles

# Syntax check
ansible-playbook -i inventories/prod playbooks/converge.yml --syntax-check
ansible-playbook -i inventories/prod playbooks/deploy.yml --syntax-check
```

Output esperado del lint:

```
Passed: 0 failure(s), 0 warning(s) in 12 files processed.
Last profile that met the validation criteria was 'production'.
```

---

## Cadena de producción (workflow)

`.github/workflows/production-hetzner.yml` es la única cadena de producción de Hetzner: reemplaza al deploy y al Apply de Terraform separados, y también define la publicación del frontend en Cloudflare Pages (contrato y requisitos operativos en [Publicación del frontend](../../docs/architecture/overview.md#publicación-del-frontend-cloudflare-pages)). Esta sección describe lo que el repositorio declara. La configuración externa (reglas del environment `production-hetzner`, alcance de los secretos, ajustes y comportamiento de HCP Terraform, configuración real del proyecto de Pages) vive fuera del repositorio y no se puede verificar desde él. La instalación desde cero y sus requisitos están en [`docs/operations/runbooks/instalacion-nueva.md`](../../docs/operations/runbooks/instalacion-nueva.md).

### Intención

- **push a `main`**: todo push a `main` dispara el workflow (sin filtro de rutas). Clasifica el rango desde el commit del último run *push* exitoso de este workflow hasta el commit actual (`.github/scripts/production-range-base.sh` + `classify-production.sh`). Si no hay un run exitoso previo, o su commit no es ancestro del actual, clasifica el árbol completo. Así un run fallido, cancelado o que quedó obsoleto porque otro commit (aunque no toque producción) avanzó `main` queda cubierto por el run de ese commit. Fallido, cancelado o reemplazado nunca avanza la base; un rango sin cambios de producción termina como no-op limpio.
- La documentación (`*.md`) bajo `infra/ansible` e `infra/terraform` no cuenta como cambio de producción; tampoco `frontend/README.md`.
- **workflow_dispatch**: intención explícita por inputs (`run_terraform_apply`, builds, configuración, deploy, rollback, `run_frontend_publish`); no avanza el rango. `run_frontend_publish` es `false` por defecto (un dispatch existente se comporta igual) y no se combina con un break-glass.
- Dos conceptos independientes, `terraform_required` y `deploy_required`, dan cuatro clases: solo Terraform, solo Deploy, Terraform → Deploy, o no-op limpio (el run termina en éxito sin pasar por ningún gate). Con Terraform, el plan decide si hay Apply: un plan sin cambios no aplica nada.
- Un tercero, `frontend_required` (cambios bajo `frontend/`), agrega la publicación en Pages al final de esa clase: `frontend_only`, `apply_then_frontend`, `deploy_then_frontend` o `apply_then_deploy_then_frontend`. Sin cambios del frontend las cuatro clases anteriores no cambian; un cambio solo del frontend no construye imágenes, no lee HCP ni toca el servidor, y no requiere `TF_API_TOKEN`.
- Solo `main` puede iniciar producción.

### Autoridad

Solo los jobs `terraform_plan` y `production` usan el environment `production-hetzner` y credenciales de producción: `terraform_plan` solo `TF_API_TOKEN`; `production` además SSH, secretos de la app y token de Pages. Clasificación, builds, selección y escaneo corren antes, solo con `GITHUB_TOKEN`. No hay acceso SSH ni lectura de HCP antes del primer gate.

Con Terraform hay **dos aprobaciones del mismo environment**, con propósitos distintos: la de `terraform_plan` autoriza usar las credenciales de HCP para planificar este commit; la de `production` se pide cuando el plan definitivo ya está revisado en el resumen del run y autoriza aplicar **ese** saved plan y seguir con el deploy y el frontend. Sin Terraform (solo deploy o solo frontend) `terraform_plan` se salta y hay una sola aprobación, la de `production`. Un plan sin cambios sin nada más que hacer no llega a `production`.

`production` corre solo si: sin Terraform, `terraform_plan` quedó **saltado**; con Terraform, `terraform_plan` terminó en **éxito** con `has_changes` exactamente `true` (aplicar) o `false` (solo si además hay deploy o frontend). Un fallo, una cancelación o una salida vacía o inválida dejan `production` sin correr y `conclude` en fallo: un job saltado nunca cuenta como trabajo hecho.

La concurrencia (`piedrazul-production-hetzner`, `cancel-in-progress: false`, `queue: max`) se aplica a ambos jobs: los pendientes esperan en cola en vez de reemplazarse. El orden de la cola no define la corrección; la frescura, la revalidación del saved plan y el rango acumulado sí.

#### Plan de Terraform (`terraform_plan`)

Workspace verificado en modo `apply` → espera a que ningún run ocupe el workspace → state version previo → frescura → `hcp-state.sh upload`: configuración **provisional** (sin `auto-queue-runs`, no speculative; no pasa a ser la configuración actual del workspace salvo que su run se aplique; si HCP no la registra como provisional, falla: nunca se recurre a una no provisional) → `hcp-state.sh create-run`: **saved plan** (`save-plan`, `auto-apply=false`, mensaje del intento `HCP_RUN_MESSAGE`) → `hcp-state.sh plan`: con cambios, `planned_and_saved` confirmable; sin cambios, `planned_and_finished` (terminal: nada que aplicar ni descartar); el plan se liga al state version capturado antes de crear el run (`EXPECTED_STATE`, `sv-…` o `none`) y a la configuración subida. HCP **no siempre informa** la relación `input-state-version` (un saved plan real `planned_and_saved` la omitió): si la informa, debe ser el estado capturado (otra cosa es una contradicción y falla, sin recurrir al estado actual); en todos los casos el estado actual, leído después del plan, debe seguir siendo el capturado (`none` solo confirmado por dos lecturas; un estado existente vacío es un `sv-…`, nunca `none`). Como cada state version nuevo tiene un id nuevo (también un rollback), un actual sin cambios entre la captura y la relectura acota el estado que el plan pudo leer; además HCP descarta un saved plan si el estado cambia antes de confirmarlo. El resumen muestra la ligadura (`relationship` o `bracketed`). Con cambios, el JSON del plan es obligatorio: `plan-review.sh` lo convierte en la **revisión saneada** del resumen (destruir, reemplazar con motivo y rutas que lo fuerzan, actualizar, crear, olvidar, importar, drift, outputs; rutas de atributos y solo valores clasificados en `plan-review-classes.json`, nunca sensibles ni no clasificados: el repositorio es público) y sus conteos deben coincidir con los del plan de HCP. Acciones desconocidas, cambios diferidos o una revisión demasiado grande fallan. Frescura otra vez: un commit viejo no sigue y su saved plan se descarta. Si el job falla o se cancela tras crear el run, lo descarta (o lo cancela si planifica). Si el run aparece confirmado fuera de GitHub, lo observa sin cancelarlo y falla.

Con deploy, `terraform_plan` y `production` no empiezan hasta que `image_scan` terminó en éxito (todos los intentos de escaneo de las identidades conocidas antes del gate completados con evidencia) y `image_policy` (evaluación preliminar de la política de vulnerabilidades sobre esas identidades) no bloqueó: un bloqueo ahí no pide la aprobación del environment ni llega al Apply (ver [Escaneo y política](#escaneo-y-política-de-vulnerabilidades-antes-de-mutar)).

Dentro de `production`, en orden:

0. **Preparación del frontend** (si `frontend_required`, solo lectura): destino declarado, artefacto validado, wrangler fijado y **sondeo** del proyecto de Pages (`pages-release.sh probe`). La identidad y la autoridad (declarado, rama, subdominio, subida directa) se exigen siempre; un proyecto ausente o incompleto (sin el dominio asociado, p. ej. tras un Apply parcial) solo se admite cuando este run aplica Terraform, que es quien lo crea o completa. Si falla, no hay ninguna mutación de producción.
1. **Workspace y estado previo** (si hay Terraform o deploy): `hcp-state.sh workspace` verifica que el id sea el workspace declarado (nombre y organización), con ejecución remota y permiso de leer su estado; si el run aplica, además que el token pueda encolar y confirmar runs (`can-queue-run`, `can-queue-apply`) y que el workspace **no** tenga `auto-apply` ni `auto-apply-run-trigger`. Después espera a que ningún run reciente ocupe el workspace (terminados, también fallidos, y saved plans sin confirmar no lo ocupan; uno activo o a la espera de una resolución sí) y captura el state version previo: `none` solo si HCP lo confirma (`current-state-version` responde 404 **y** la lista de state versions del workspace está vacía, con el workspace ya leído); un 401/403/404 del workspace, cualquier otro código o una contradicción fallan cerrado.
2. **Frescura**: el commit del run debe ser la punta actual de `main` (`verify-main-tip.sh`), comprobado justo antes de la primera mutación (antes de confirmar el Apply, o antes de tocar el servidor si no hay Apply). Iniciada la cadena, termina aunque `main` avance; un re-run de un commit que ya no es la punta falla aquí sin mutar nada.
3. **Confirmación del saved plan y resultado del Apply** (si `terraform_plan` informó cambios): si faltaba el proyecto de Pages (o estaba incompleto), el plan revisado debe **mostrar** la creación de lo que falta (proyecto y asociación del dominio, o solo la asociación); `unknown`, `ambiguous` u otra acción fallan sin Apply. Luego `hcp-state.sh apply` revalida en ese momento: workspace sin auto-apply y con permiso de confirmar; run propio (mensaje de `terraform_plan`, saved plan, `auto-apply=false`), confirmable y con cambios; mismo plan y misma configuración que lo revisado; plan de como mucho una hora; ningún otro run ocupando el workspace; frescura; y, como última comprobación antes de releer el run, el estado: el de entrada (si HCP lo informa) y el actual deben ser el del plan revisado. Un cambio de estado posterior hace que HCP descarte el saved plan (la última lectura del run lo ve descartado, o el POST responde 409). Envía **una sola** solicitud `POST /runs/<id>/actions/apply` y nunca la repite: `202` → aceptada cuando HCP muestra el run confirmado; `409` → se relee (descartado por HCP o confirmado por otro); sin respuesta o 5xx → se relee con plazo, y solo un run confirmado después de la solicitud cuenta como aceptado. Un run confirmado fuera de GitHub (antes o en lugar de la solicitud) nunca se atribuye a la aprobación: se observa sin cancelarlo hasta que termina, se informa (`out_of_band`) y la cadena falla antes del servidor y de Pages. Aceptar la solicitud no es aplicar: `hcp-state.sh run-state` espera el resultado real y determina el state version que el deploy consume: si el apply del run tiene state versions enlazados, el actual del workspace debe ser uno de ellos y estar enlazado a ese run; si no (plan sin cambios), el run debe ser `planned_and_finished` y el actual debe seguir siendo el del plan (también `none`). Otro run, un estado reemplazado o ambiguo, o un run de otro workspace fallan cerrado. Si `production` falla o se cancela antes de confirmar, el saved plan se descarta; si HCP ya aceptó el Apply, no se toca y se informa.
4. **Outputs**: se leen de ese state version exacto, solo si sigue siendo el actual antes y después de leerlos. Sin Apply, el state version es el capturado al inicio; un workspace sin estado (`none`) no tiene servidor y el deploy falla (una instalación nueva necesita `run_terraform_apply`).
5. **Proyecto de Pages tras Terraform** (si `frontend_required`): `pages-release.sh preflight` estricto, antes de tocar el servidor y de wrangler. El proyecto, también el que acaba de crear o completar el Apply, debe existir y ser el declarado, de subida directa y con el dominio asociado.
5b. **Inicialización del servidor** (si corre converge): `host-ready.sh` espera, con plazo (10 min), a que cloud-init termine (`cloud-init status --wait`) y lee su estado final en JSON. Solo `done` sin errores ni errores recuperables sigue; degradado (`degraded done` o cualquier error recuperable), error, todavía corriendo, deshabilitado, salida ilegible o con forma inesperada (tipos de `status`, `extended_status`, `errors` y `recoverable_errors` validados; los opcionales pueden faltar en versiones antiguas), SSH fallido, código de salida distinto de 0 aunque el JSON diga éxito, o plazo vencido fallan cerrado, cada uno con su diagnóstico. No verifica la clave del host (`StrictHostKeyChecking=no`, como el resto de la cadena): limitación previa. Que SSH responda no prueba que cloud-init haya terminado (usuarios, paquetes, configuración de SSH).
6. **Último éxito**: lectura (solo lectura) de `last-success.env` del servidor.
7. **Selección** (`select-release.sh`): backend = rollback > build de este run > último éxito; postgres = build de este run > último éxito. Build y rollback llegan con la identidad inmutable resuelta antes del gate; el reuso toma la identidad exacta registrada. Ausente, malformada o ambigua falla cerrado. Sin deploy de aplicación ambas son reuso.
8. **Escaneo y política antes de mutar** (`scan-evidence.sh` + `vuln-policy.sh`): para cada imagen que el run aplica, build/rollback y keycloak/traefik deben tener un `summary.json` válido de `image_scan` sobre la misma referencia exacta (keycloak y traefik, además, exactamente la `keycloak_image_ref` / `traefik_image_ref` que recibe converge); el backend o postgres en reuso se escanea aquí, con la identidad recién leída, usando la misma acción. Después, la política decide sobre **todas** esas imágenes (evaluación autoritativa): CRITICAL sin disposición aprobada, fallo operativo del escáner o evidencia incompleta/vencida → el paso falla y no hay mutación. Un break-glass autorizado se registra en un issue `security-break-glass` antes de mutar. Bloquea también una cadena de escaneo incompleta o no verificable (ver abajo).
9. **Converge / deploy** con esas referencias exactas (con frescura justo antes si no hubo Apply). Ambos convergen postgres y reconcilian las autoridades de la base antes de cualquier consumidor; deploy migra el esquema con el backend construido en este run (misma identidad exacta) antes de arrancarlo. Si la migración falla, el paso de deploy falla y no se llega a la verificación ni al registro.
10. **Identidad en ejecución** (tras converge: postgres, keycloak y traefik; tras deploy: postgres y backend) y **health** (contenedores —keycloak también si corrió converge—, y luego `actuator/health` del backend y el discovery OIDC de Keycloak).
11. **Registro** del último éxito, solo si hubo deploy de aplicación y todo lo anterior pasó.
12. **Publicación del frontend** (si `frontend_required`, y solo con el preflight del paso 5 en éxito): con frescura justo antes si el run no hizo antes otra mutación de producción; `wrangler pages deploy` del artefacto validado y verificación del deployment creado por esa invocación. Si la preparación del paso 0 falla, el run no hace ninguna mutación de producción (ni plan, ni Apply, ni servidor, ni Pages), aunque los jobs previos sin privilegios del run pueden haber producido ya artefactos (imágenes en GHCR, el artifact del frontend, evidencia de escaneo). Ver [Publicación del frontend](../../docs/architecture/overview.md#publicación-del-frontend-cloudflare-pages).
13. **Limpieza del run de Terraform** (`hcp_cleanup`, siempre que este intento intentó crear un run, también si el job falla, se cancela o vence): solo sobre runs con el mensaje de este intento; si GitHub no llegó a registrar el id (la creación falló o se cortó tras crearlo en HCP), lo busca por ese mensaje entre los 20 runs más recientes. Esperando confirmación → se descarta; planificando → se cancela; confirmado o aplicando → no se toca y se informa como Apply aceptado (cancelar el job no deshace un Apply que HCP ya aceptó; el siguiente run reconcilia el estado).

El resumen del job muestra el plan (conteos, enlace y si requirió confirmación), qué pasó con el run de este intento, el state version consumido, las identidades (y las anteriores), el veredicto de la política por imagen y la primera etapa que falló: credenciales, preparación del frontend, frescura, HCP (workspace, plan, confirmación, estado), proyecto de Pages tras Terraform, acceso al servidor, selección, escaneo previo, política de vulnerabilidades, registro de break-glass, deploy, identidad en ejecución, health, registro o publicación en Pages. El job `conclude` hace que la conclusión del run sea success solo si producción terminó bien (incluida la publicación del frontend cuando hacía falta) o no hacía falta; esa conclusión es la base del siguiente rango.

### Identidad inmutable

`.github/scripts/resolve-amd64-image.sh` resuelve la identidad **una sola vez**: lee el índice OCI (`docker buildx imagetools inspect --raw`), calcula su digest sobre los bytes recibidos y elige su único manifiesto `linux/amd64` que no sea attestation. Falla si el índice no se puede leer, no es un índice multi-plataforma, no coincide con el digest pedido o no tiene exactamente un hijo `linux/amd64`.

| imagen | identidad |
|---|---|
| backend, build | digest que devuelve el mismo `docker/build-push-action`, leído por digest; el tag no se vuelve a consultar |
| backend, rollback | `resolve_rollback` lee el `image_tag` una vez (también verifica que exista en GHCR) |
| backend / postgres, reuso | la registrada en `last-success.env`, leída tras el gate |
| postgres, build | igual que backend build |
| keycloak | referencia fijada `<tag>@sha256:<índice>` de `infra/compose/keycloak.yml`, resuelta por digest en `image_targets` (solo si corre converge) |
| traefik | referencia fijada `<tag>@sha256:<índice>` de `infra/compose/traefik.yml`, resuelta por digest en `image_targets` (solo si corre converge) |

La misma referencia `repo@sha256:<linux/amd64>` es la que se escanea, la que recibe Ansible, la que se verifica en ejecución y (backend y postgres) la que se registra. `resolve-amd64-image.sh` acepta `<repo>:<tag>`, `<repo>@sha256:<índice>` y `<repo>:<tag>@sha256:<índice>`; con digest lee solo por digest y el tag queda como etiqueta.

### Escaneo y política de vulnerabilidades antes de mutar

`image_targets` reúne las imágenes cuya identidad se conoce antes del gate: backend y postgres de build o rollback (marcados "no desplegada" y `applied: false` si el run no los despliega), keycloak y traefik solo si corre converge. `image_scan` las escanea antes del gate, `image_policy` evalúa la política sobre las que el run aplica y `production` espera a ambos. Las imágenes en reuso se escanean dentro de `production`, después de leer el último éxito y antes de cualquier mutación. Ambos escaneos usan la acción local `.github/actions/trivy-scan`: Trivy 0.74.0 (imagen oficial fijada por digest), `--image-src remote --platform linux/amd64 --scanners vuln`, sin socket de Docker, sin credenciales ni variables de entorno (el contenedor solo monta su caché y el directorio del reporte). Cada escaneo publica en el resumen el ref escaneado, la fecha del escaneo, la versión de Trivy, la fecha de la DB y los CRITICAL/HIGH, y sube `trivy.json`, `trivy-version.json` y `summary.json` como artifact `image-scan-<fase>-<imagen>-<run_id>` (30 días; fase `selected` antes del gate, `production` para el reuso). Esos artifacts son también la única evidencia histórica elegible para un break-glass.

- Cada intento deja un resultado explícito en `summary.json` (`outcome`) y en el resumen:
  - `scanned`: evaluado, con los CRITICAL/HIGH (que pueden ser cero). La acción no falla por hallazgos (`--exit-code 0`): decide la política.
  - `operational_failure`: registry, DB de vulnerabilidades o Trivy fallaron, o el reporte no corresponde a la referencia (una referencia de keycloak o traefik sin resolver falla `image_targets`: sin identidad no hay despliegue). Sin conteos (`null`), con anotación de error y `FALLO OPERATIVO`: **no equivale a cero hallazgos**, y la política lo bloquea.
- Solo el intento de Trivy (descarga del registry, DB, ejecución, reporte que no corresponde a la referencia) se registra como `operational_failure`; la acción termina bien y deja su `summary.json`. Cualquier otro error (entrada inválida, generación de la evidencia, subida del artifact, checkout) hace fallar el job.
- **Política** (`.github/scripts/vuln-policy.sh`, contrato completo en [politica-vulnerabilidades-imagenes.md](../../docs/operations/runbooks/politica-vulnerabilidades-imagenes.md)): lee `trivy.json` hallazgo por hallazgo y exige referencia, plataforma `linux/amd64`, conteos coherentes con `summary.json`, escaneo de menos de 24 h y DB de menos de 48 h al escanear. CRITICAL → necesita una disposición vigente cuya aprobación humana se verifica en GitHub (PR fusionado a `main`, revisor en `vars.VULNERABILITY_RISK_APPROVERS`, mismo contenido aprobado); HIGH → solo se informa. Rollback y reuso no tienen excepción implícita: se escanean y evalúan igual que un build.
- **Cadena incompleta o no verificable → `production` no muta** (no es un `operational_failure`): `image_scan` fallido o cancelado, `summary.json` ausente, malformado, duplicado, de otra referencia o con un resultado incoherente, o una imagen aplicada sin intento de escaneo. Un artifact ausente no prueba que Trivy haya intentado el escaneo.
- `image_policy` y `production` descargan la evidencia previa con `current-scan-evidence.sh` (no con `actions/download-artifact`, que ante un digest distinto solo advierte): artifacts de este run, sin nombres repetidos, con el digest que registró GitHub; cualquier diferencia falla el paso. Después la validan y muestran el resultado de cada imagen que aplica.
- Los escaneos de keycloak y traefik son exactamente lo que converge despliega y pasan por la misma política.

Los `FROM` de `backend/Dockerfile` e `infra/postgres/Dockerfile` están fijados por digest del índice OCI (`imagen:tag@sha256:…`), así buildx toma el hijo correcto para `linux/amd64` y `linux/arm64`. Para actualizar una base se resuelve el nuevo índice (`docker buildx imagetools inspect <imagen>:<tag>`) y se reemplaza el digest.


---

## Flujo completo — primer deploy

```
GitHub Actions (workflow)
  │
  ├── workspace sin estado (none, confirmado por HCP)
  ├── saved plan (configuración provisional, auto-apply=false) → revisión en el resumen
  │     → segunda aprobación → plan crea el servidor: KC_PLATFORM_ADMIN_INITIAL_PASSWORD
  │     válida (keycloak-admin-preflight.yml, en el runner; si no, sin Apply)
  │     → una sola confirmación del Apply → resultado del Apply
  │     (crea servidor, firewall, proyecto de Pages de subida directa, DNS, asociación del dominio)
  ├── state version de ese run → IP del servidor
  ├── preflight estricto de Pages (si se publica el frontend)
  ├── espera de cloud-init (host-ready.sh: done sin errores, con plazo)
  ├── Genera hosts.runtime.yml con la IP
  ├── Sin last-success.env: exige build de backend y de postgres (identidades exactas)
  └── Corre: ansible-playbook converge.yml -e backend_image_ref=… -e postgres_image_ref=…
        │
        ├── common     → timezone, paquetes base
        ├── docker_host → Docker CE instalado y corriendo
        ├── hardening  → fail2ban, sysctl
        ├── setup.yml
        │     ├── valida KC_BACKEND_CLIENT_SECRET / KC_AUTOMATION_CLIENT_SECRET
        │     │   (KC_PLATFORM_ADMIN_INITIAL_PASSWORD se valida después, solo si Keycloak es P2)
        │     ├── copia compose files + keycloak/ + postgres/
        │     ├── genera .env
        │     ├── genera traefik/dynamic/routes.yml (file provider)
        │     ├── valida y descarga por digest backend/postgres, keycloak y traefik → image.env exacto
        │     ├── postgres (PGDATA vacío → 01-init-databases.sh) + reconciliación
        │     └── levanta Traefik
        ├── keycloak_lifecycle.yml → P2 (schema keycloak vacío)
        │     ├── bootstrap-admin service (Keycloak detenido) → admin temporal aleatorio
        │     ├── kc.sh import (un solo uso) → piedrazul (secreto del backend aleatorio)
        │     ├── arranca Keycloak (start, sin --import-realm)
        │     ├── establece la automatización y la forma/roles del backend
        │     ├── crea el administrador humano permanente (KC_PLATFORM_ADMIN_INITIAL_PASSWORD,
        │     │   contraseña temporal + CONFIGURE_TOTP/UPDATE_PASSWORD)
        │     ├── elimina la autoridad temporal y verifica que Keycloak la rechaza
        │     └── reclasifica (P1) → fija KC_BACKEND_CLIENT_SECRET y verifica el backend
        └── refresh_backend.yml → sin backend todavía: no hace nada

  └── deploy.yml (app_migrate=true: backend de build)
        ├── postgres + reconciliación
        ├── migrate (misma identidad que el backend) → esquema desde cero
        └── backend (Flyway deshabilitado, JPA validate)
```

## Flujo completo — deploy de nueva imagen

```
GitHub Actions (production-hetzner.yml)
  │
  ├── build multi-arch → push a GHCR → identidad exacta backend@sha256:<linux/amd64>
  ├── escaneo Trivy de esa misma identidad + política preliminar (production espera a ambos)
  └── job production (gate production-hetzner)
        ├── state version actual → IP; lee last-success.env (postgres en reuso)
        ├── evidencia: escaneo previo del backend; escaneo aquí del postgres en reuso
        ├── política de vulnerabilidades (autoritativa) sobre backend y postgres
        ├── frescura: el commit es la punta de main
        ├── deploy.yml -e backend_image_ref=<build> -e postgres_image_ref=<último éxito> -e app_migrate=true
        │     ├── pull por digest → image.env exacto
        │     ├── postgres + reconciliación de autoridades (flock)
        │     ├── migrate con <build> (flock; si falla, se detiene aquí)
        │     └── docker_compose_v2 postgres + backend
        ├── verify-release.yml identity → contenedores corren esas referencias exactas
        ├── verify-release.yml health   → contenedores healthy
        ├── health público (actuator/health, discovery OIDC)
        └── record-release.yml → last-success.env con las referencias exactas
```

## Flujo completo — converge posterior (sin cambios de imagen)

```
GitHub Actions (production-hetzner.yml — cambios en infra/ansible, infra/compose, infra/traefik o el realm/tema de Keycloak)
  │
  └── Corre: ansible-playbook converge.yml -e backend_image_ref=… -e postgres_image_ref=…
        (sin deploy de aplicación: ambas son el último éxito del servidor)
        │
        ├── common/docker_host/hardening → idempotentes, sin cambios
        ├── setup.yml
        │     ├── valida las credenciales técnicas de Keycloak
        │     ├── copia archivos (sin cambios → no notifica handler)
        │     ├── genera .env (sin cambios → no notifica handler)
        │     ├── postgres + reconciliación (idempotente)
        │     └── docker_compose_v2 Traefik → no-op
        ├── keycloak_lifecycle.yml
        │     ├── identidad de Keycloak = la del commit; base no posterior a la imagen
        │     ├── keycloak recreado solo si cambió su configuración (p. ej. versión nueva → migra la base)
        │     ├── P1 → secreto del backend sin cambios (o rotado) y verificado; sin autoridad temporal
        │     └── informa el estado del administrador humano (no lo toca)
        └── refresh_backend.yml → recrea el backend solo si cambió su configuración
```
