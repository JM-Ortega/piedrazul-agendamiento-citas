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
│   └── record-release.yml               # Registra el último despliegue exitoso
└── roles/
    ├── common/                          # Paquetes base y timezone
    ├── docker_host/                     # Instalación y configuración de Docker
    ├── hardening/                       # Hardening del servidor
    └── app/                             # Gestión del stack de la aplicación
        ├── tasks/
        │   ├── main.yml                 # Punto de entrada (importa setup.yml)
        │   ├── setup.yml                # Copia archivos, genera .env, levanta stack
        │   ├── init_keycloak.yml        # Bootstrap de identidad (solo una vez)
        │   ├── deploy.yml               # Rollout de backend y postgres
        │   ├── release_images.yml       # Valida y descarga por digest las imágenes propias (setup y deploy)
        │   ├── verify_running_images.yml # Identidad exacta en ejecución
        │   ├── verify_health.yml        # Health de los contenedores propios
        │   └── record_release.yml       # Escribe el último despliegue exitoso
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
  - name: community.general   # keycloak_*, timezone
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
kc_bootstrap_admin_user: kc-bootstrap
kc_admin_user: kc-admin
```

### `host_vars/vps.yml` (Hetzner)

```yaml
kc_hostname: auth.piedrazul.narvaezlab.dev
api_public_domain: api.piedrazul.narvaezlab.dev
acme_email: jortegan@unicauca.edu.co
```

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
├── app/setup.yml       → copia archivos, genera .env, levanta stack
└── app/init_keycloak.yml → bootstrap de identidad (solo si no existe el marker)
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
  playbooks/deploy.yml
```

`converge.yml` recibe las mismas dos variables.

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
2. Lee `KC_BACKEND_CLIENT_SECRET` del `.env` existente usando `slurp` + filtro Jinja — si no existe, genera uno nuevo con `openssl rand -hex 32` y lo guarda como `app_kc_backend_secret`
3. Copia `infra/compose/` → `/opt/piedrazul/`
4. Copia `infra/keycloak/` → `/opt/piedrazul/keycloak/` (realm JSON + theme JAR)
5. Copia `infra/postgres/` → `/opt/piedrazul/postgres/` (script de init de DB)
6. Genera `.env` desde `env.j2` usando `app_kc_backend_secret`
7. `flush_handlers` — aplica cualquier restart pendiente de forma controlada
8. Levanta el stack con `docker_compose_v2 state: present`

#### Identidad de las imágenes propias

Backend y postgres se despliegan siempre por identidad exacta: `image.env` contiene `BACKEND_IMAGE` y `POSTGRES_IMAGE` como `repo@sha256:<manifiesto linux/amd64>`, y Compose las exige (`${BACKEND_IMAGE:?}` en `backend.yml`, `${POSTGRES_IMAGE:?}` en `prod.yml`). Ansible no selecciona ni resuelve tags: `release_images.yml` valida el formato contra `backend_image` / `postgres_image` y descarga por digest; sin identidad exacta válida falla antes de tocar `image.env`.

El servidor guarda dos estados distintos:

| Archivo | Contenido | Quién lo escribe |
|---------|-----------|------------------|
| `image.env` | Entrada de Compose; puede contener un candidato aún no verificado o fallido | `setup.yml` y `deploy.yml`, antes de levantar servicios |
| `last-success.env` (`release_state_file`) | Último despliegue **exitoso**: `BACKEND_IMAGE_REF`, `POSTGRES_IMAGE_REF` (exactas), sus digests de índice y tags (metadato), `RELEASE_COMMIT` y `RELEASE_RUN` | solo `record_release.yml` |

`record_release.yml` corre como último paso del workflow, después de que `deploy.yml` terminó, de que `verify-release.yml` confirmó identidad en ejecución y health de los contenedores, y de que pasaron los health checks públicos. Antes de escribir vuelve a verificar identidad y health. La escritura es atómica.

Identidad en ejecución (`verify_running_images.yml`): el contenedor existe y corre, su `Config.Image` es la referencia exacta esperada (un contenedor creado desde un tag no cuenta, aunque el contenido coincida) y la imagen local que ejecuta está registrada por el daemon bajo ese digest (`RepoDigests`). El health (`verify_health.yml`) se verifica por separado: un backend `healthy` no prueba que corra el contenido esperado.

Si cualquier paso falla o el run se cancela antes del registro, `last-success.env` no cambia: el siguiente reuso vuelve al último éxito, aunque `image.env` o el contenedor tengan el candidato fallido. No hay rollback automático del contenedor; para eso existe el rollback explícito del backend. Un primer deploy fallido en un servidor nuevo no deja estado reusable.

`last-success.env` es la única fuente de reuso y la lee el workflow, nunca Ansible (ver [Cadena de producción](#cadena-de-producción-workflow)). El formato anterior (`backend-deployed.env`, solo tag) no se migra: un servidor sin `last-success.env` necesita un deploy con build de backend y de postgres (o rollback de backend más build de postgres); un reuso falla cerrado.

#### `init_keycloak.yml`

Corre **una sola vez** — controlado por el marker `/opt/piedrazul/.keycloak_initialized`. Si el marker existe, todo el bloque se salta. Pasos:

1. Espera `http://127.0.0.1:9000/health/ready` (puerto management de Keycloak)
2. Espera que el realm `piedrazul` esté importado (retry con `keycloak_realm`)
3. Crea usuario admin permanente `kc-admin` en master realm (`keycloak_user`)
4. Asigna rol `admin` a `kc-admin` (`keycloak_user_rolemapping`)
5. Aplica `KC_BACKEND_CLIENT_SECRET` al client `piedrazul-backend` en realm `piedrazul` (`keycloak_client`)
6. Elimina usuario bootstrap temporal `kc-bootstrap` (`keycloak_user`)
7. Reinicia solo el backend para que tome el secret correcto
8. Crea el marker `.keycloak_initialized`

> **Para forzar re-inicialización:** eliminar `/opt/piedrazul/.keycloak_initialized` del servidor y correr `converge` de nuevo.

#### `deploy.yml`

Rollout de las imágenes propias. Pasos:

1. Verifica que `.env` existe y que `KC_BACKEND_CLIENT_SECRET` no está vacío
2. Valida y descarga por digest `backend_image_ref` y `postgres_image_ref` (`release_images.yml`)
3. Escribe `image.env` con esas referencias exactas
4. `docker_compose_v2` de `postgres` y `backend` (`pull: missing`: las imágenes ya están por digest)

La verificación y el registro del último éxito son pasos posteriores del workflow (`verify-release.yml`, `record-release.yml`).

#### Handler: `Restart app stack`

Se dispara cuando cambian los compose files o el `.env`. Hace `docker_compose_v2 state: present` — Compose reconcilia solo lo que cambió.

---

## Template `.env` (`env.j2`)

El archivo `.env` se genera en cada converge desde este template. Las variables vienen de tres fuentes:

| Origen | Ejemplos |
|--------|----------|
| `group_vars/all.yml` | `compose_project_name`, `kc_realm`, `kc_port` |
| `host_vars/vps.yml` | `kc_hostname`, `api_public_domain`, `acme_email` |
| Variables de entorno del runner (GitHub Secrets) | `DB_PASSWORD`, `KC_DB_PASSWORD`, `KC_ADMIN_PASSWORD`, `KC_BOOTSTRAP_ADMIN_PASSWORD`, `CLOUDFLARE_DNS_API_TOKEN` |
| `set_fact` calculado por Ansible | `app_kc_backend_secret` (leído del `.env` existente o generado) |

`KC_BACKEND_CLIENT_SECRET` **nunca pasa por GitHub Secrets** — Ansible lo genera en el servidor en el primer deploy y lo preserva en deploys posteriores leyéndolo del `.env` existente.

---

## Secrets requeridos en GitHub

Los siguientes secrets deben existir en el repositorio de GitHub:

```
DB_PASSWORD                  # Password de la base de datos principal
KC_DB_PASSWORD               # Password del usuario de Keycloak en postgres
KC_BOOTSTRAP_ADMIN_PASSWORD  # Password del usuario temporal de bootstrap de Keycloak
KC_ADMIN_PASSWORD            # Password del usuario admin permanente de Keycloak
CLOUDFLARE_DNS_API_TOKEN     # Token de Cloudflare para DNS challenge de Let's Encrypt
ANSIBLE_SSH_KEY              # Clave privada SSH para que Ansible se conecte al servidor
```

El workflow los inyecta como variables de entorno antes de correr Ansible:

```yaml
- name: Run Ansible converge
  env:
    ANSIBLE_HOST_KEY_CHECKING: "False"
    DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
    KC_DB_PASSWORD: ${{ secrets.KC_DB_PASSWORD }}
    KC_BOOTSTRAP_ADMIN_PASSWORD: ${{ secrets.KC_BOOTSTRAP_ADMIN_PASSWORD }}
    KC_ADMIN_PASSWORD: ${{ secrets.KC_ADMIN_PASSWORD }}
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

### Caso 4: Secret gestionado solo en el servidor (como `KC_BACKEND_CLIENT_SECRET`)

Para secrets que no deben pasar por GitHub en ningún momento:

1. En `setup.yml`, leer el valor existente del `.env` o generarlo con `openssl` / `ansible.builtin.command`
2. Guardarlo como `set_fact` con prefijo del rol (`app_`)
3. Usarlo en `env.j2` como variable Jinja normal

---

## Convenciones de naming

Ansible-lint (profile `production`) exige que las variables definidas dentro de un rol lleven el nombre del rol como prefijo para evitar colisiones.

| Rol | Prefijo requerido | Ejemplos |
|-----|-------------------|---------|
| `app` | `app_` | `app_kc_backend_secret`, `app_env_file`, `app_kc_initialized` |
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

`.github/workflows/production-hetzner.yml` es la única cadena de producción de Hetzner: reemplaza al deploy y al Apply de Terraform separados. Esta sección describe lo que el repositorio declara. La configuración externa (reglas del environment `production-hetzner`, alcance de los secretos, comportamiento de HCP Terraform) vive fuera del repositorio y la cadena no se ha ejercido todavía contra producción.

### Intención

- **push a `main`**: todo push a `main` dispara el workflow (sin filtro de rutas). Clasifica el rango desde el commit del último run *push* exitoso de este workflow hasta el commit actual (`.github/scripts/production-range-base.sh` + `classify-production.sh`). Si no hay un run exitoso previo, o su commit no es ancestro del actual, clasifica el árbol completo. Así un run fallido, cancelado o que quedó obsoleto porque otro commit (aunque no toque producción) avanzó `main` queda cubierto por el run de ese commit. Fallido, cancelado o reemplazado nunca avanza la base; un rango sin cambios de producción termina como no-op limpio.
- La documentación (`*.md`) bajo `infra/ansible` e `infra/terraform` no cuenta como cambio de producción.
- **workflow_dispatch**: intención explícita por inputs (`run_terraform_apply`, builds, configuración, deploy, rollback); no avanza el rango.
- Dos conceptos independientes, `terraform_required` y `deploy_required`, dan cuatro clases: solo Apply, solo Deploy, Apply → Deploy, o no-op limpio (el run termina en éxito sin pasar por el gate).
- Solo `main` puede iniciar producción.

### Autoridad

Solo el job `production` usa el environment `production-hetzner` y credenciales de producción (HCP Terraform, SSH, secretos de la app); una aprobación cubre toda la cadena. Clasificación, builds, selección y escaneo corren antes, solo con `GITHUB_TOKEN`. No hay acceso SSH ni lectura de HCP antes del gate. La concurrencia (`piedrazul-production-hetzner`, `cancel-in-progress: false`, `queue: max`) se aplica a ese job: los jobs pendientes esperan en cola en vez de reemplazarse. El orden de la cola no define la corrección; la frescura y el rango acumulado sí.

Con deploy, `production` no empieza hasta que `image_scan` terminó en éxito (todos los intentos de escaneo de las identidades conocidas antes del gate completados con evidencia) (ver [Escaneo](#escaneo-antes-de-mutar-solo-reporte)).

Dentro de `production`, en orden:

1. **Frescura**: el commit del run debe ser la punta actual de `main` (`verify-main-tip.sh`), comprobado justo antes de la primera mutación (antes del Apply, o antes de tocar el servidor si no hay Apply). Iniciada la cadena, termina aunque `main` avance; un re-run de un commit que ya no es la punta falla aquí sin mutar nada.
2. **Apply** (si aplica): espera a que el último run del workspace esté en un estado final (un run final fallido, `errored` o `policy_soft_failed`, no bloquea; uno activo o a la espera de una resolución sí), captura el state version previo, crea el run de HCP en esta misma ejecución y lo confirma. `hcp-state.sh run-state` determina el state version que el deploy consume: si el apply del run tiene state versions enlazados, el actual del workspace debe ser uno de ellos y estar enlazado a ese run; si no, el run debe ser `planned_and_finished` y el actual debe seguir siendo el previo. Otro run, un estado reemplazado o ambiguo, o un run de otro workspace fallan cerrado.
3. **Outputs**: se leen de ese state version exacto, solo si sigue siendo el actual antes y después de leerlos. Sin Apply, el state version es el capturado al inicio.
4. **Último éxito**: lectura (solo lectura) de `last-success.env` del servidor.
5. **Selección** (`select-release.sh`): backend = rollback > build de este run > último éxito; postgres = build de este run > último éxito. Build y rollback llegan con la identidad inmutable resuelta antes del gate; el reuso toma la identidad exacta registrada. Ausente, malformada o ambigua falla cerrado. Sin deploy de aplicación ambas son reuso.
6. **Escaneo antes de mutar** (`scan-evidence.sh`): para cada imagen que el run aplica, build/rollback y keycloak/traefik deben tener un `summary.json` válido de `image_scan` sobre la misma referencia exacta; el backend o postgres en reuso se escanea aquí, con la identidad recién leída, usando la misma acción. El resultado de cada intento (`scanned` con sus hallazgos, u `operational_failure`) se informa en el resumen pero no bloquea. Sí bloquea una cadena de escaneo incompleta o no verificable (ver abajo).
7. **Converge / deploy** con esas referencias exactas (con frescura justo antes si no hubo Apply).
8. **Identidad en ejecución** y **health** (contenedores, y luego `actuator/health` del backend y el discovery OIDC de Keycloak).
9. **Registro** del último éxito, solo si hubo deploy de aplicación y todo lo anterior pasó.

El resumen del job muestra el state version consumido, las identidades (y las anteriores) y la primera etapa que falló: credenciales, frescura, HCP, acceso al servidor, selección, escaneo previo, deploy, identidad en ejecución, health o registro. El job `conclude` hace que la conclusión del run sea success solo si producción terminó bien o no hacía falta; esa conclusión es la base del siguiente rango.

### Identidad inmutable

`.github/scripts/resolve-amd64-image.sh` resuelve la identidad **una sola vez**: lee el índice OCI (`docker buildx imagetools inspect --raw`), calcula su digest sobre los bytes recibidos y elige su único manifiesto `linux/amd64` que no sea attestation. Falla si el índice no se puede leer, no es un índice multi-plataforma, no coincide con el digest pedido o no tiene exactamente un hijo `linux/amd64`.

| imagen | identidad |
|---|---|
| backend, build | digest que devuelve el mismo `docker/build-push-action`, leído por digest; el tag no se vuelve a consultar |
| backend, rollback | `resolve_rollback` lee el `image_tag` una vez (también verifica que exista en GHCR) |
| backend / postgres, reuso | la registrada en `last-success.env`, leída tras el gate |
| postgres, build | igual que backend build |
| keycloak, traefik | fijada **solo para el escaneo**: se resuelve la referencia de `infra/compose/*.yml`, pero Compose sigue desplegando por tag |

La misma referencia `repo@sha256:<linux/amd64>` es la que se escanea, la que recibe Ansible, la que se verifica en ejecución y la que se registra.

### Escaneo antes de mutar (solo reporte)

`image_targets` reúne las imágenes cuya identidad se conoce antes del gate: backend y postgres de build o rollback (marcados "no desplegada" si el run no los despliega), keycloak si corre converge o deploy y traefik solo si corre converge. `image_scan` las escanea antes del gate y `production` espera a que esos intentos terminen. Las imágenes en reuso se escanean dentro de `production`, después de leer el último éxito y antes de cualquier mutación. Ambos escaneos usan la acción local `.github/actions/trivy-scan`: Trivy 0.74.0 (imagen oficial fijada por digest), `--image-src remote --platform linux/amd64 --scanners vuln`, sin socket de Docker, sin credenciales ni variables de entorno (el contenedor solo monta su caché y el directorio del reporte). Cada escaneo publica en el resumen el ref escaneado, la versión de Trivy, la fecha de la DB y los CRITICAL/HIGH, y sube `trivy.json`, `trivy-version.json` y `summary.json` como artifact `image-scan-<fase>-<imagen>-<run_id>` (30 días; fase `selected` antes del gate, `production` para el reuso).

- Lo que se exige es el **orden**: el intento de escaneo de la identidad exacta termina antes de la mutación que puede aplicarla. Su resultado no autoriza ni bloquea mientras la política de vulnerabilidades no esté activa.
- Cada intento deja un resultado explícito en `summary.json` (`outcome`) y en el resumen:
  - `scanned`: evaluado, con conteo de CRITICAL/HIGH (que puede ser cero). Los hallazgos **no bloquean** (`--exit-code 0`).
  - `operational_failure`: registry, DB de vulnerabilidades o Trivy fallaron, el reporte no corresponde a la referencia, o la referencia de keycloak/traefik no se pudo resolver. Sin conteos (`null`), con anotación de error y `FALLO OPERATIVO`: **no equivale a cero hallazgos**. Transitorio: tampoco bloquea; cuando se active la política será bloqueante.
- Solo el intento de Trivy (descarga del registry, DB, ejecución, reporte que no corresponde a la referencia) se registra como `operational_failure`; la acción termina bien y deja su `summary.json`. Cualquier otro error (entrada inválida, generación de la evidencia, subida del artifact, checkout) hace fallar el job.
- **Cadena incompleta o no verificable → `production` no muta** (no es un `operational_failure`): `image_scan` fallido o cancelado, `summary.json` ausente, malformado, duplicado, de otra referencia o con un resultado incoherente, o una imagen aplicada sin intento de escaneo. Un artifact ausente no prueba que Trivy haya intentado el escaneo.
- `production` descarga los `summary.json` previos, los valida y muestra el resultado de cada imagen que aplica.
- El escaneo de keycloak y traefik describe el contenido actual de su tag, que puede no ser lo que corre en el servidor.

Los `FROM` de `backend/Dockerfile` e `infra/postgres/Dockerfile` están fijados por digest del índice OCI (`imagen:tag@sha256:…`), así buildx toma el hijo correcto para `linux/amd64` y `linux/arm64`. Para actualizar una base se resuelve el nuevo índice (`docker buildx imagetools inspect <imagen>:<tag>`) y se reemplaza el digest.

---

## Flujo completo — primer deploy

```
GitHub Actions (workflow)
  │
  ├── Terraform apply → state version de ese run → IP del servidor
  ├── Genera hosts.runtime.yml con la IP
  ├── Sin last-success.env: exige build de backend y de postgres (identidades exactas)
  └── Corre: ansible-playbook converge.yml -e backend_image_ref=… -e postgres_image_ref=…
        │
        ├── common     → timezone, paquetes base
        ├── docker_host → Docker CE instalado y corriendo
        ├── hardening  → fail2ban, sysctl
        ├── setup.yml
        │     ├── genera KC_BACKEND_CLIENT_SECRET (nuevo)
        │     ├── copia compose files + keycloak/ + postgres/
        │     ├── genera .env
        │     ├── valida y descarga por digest backend/postgres → image.env exacto
        │     └── levanta DB + Keycloak + Traefik
        └── init_keycloak.yml
              ├── espera Keycloak healthy (9000/health/ready)
              ├── espera realm piedrazul importado
              ├── crea usuario kc-admin en master realm
              ├── asigna rol admin a kc-admin
              ├── aplica KC_BACKEND_CLIENT_SECRET al client
              ├── elimina usuario kc-bootstrap
              ├── reinicia backend
              └── crea marker .keycloak_initialized
```

## Flujo completo — deploy de nueva imagen

```
GitHub Actions (production-hetzner.yml)
  │
  ├── build multi-arch → push a GHCR → identidad exacta backend@sha256:<linux/amd64>
  ├── escaneo Trivy de esa misma identidad (solo reporte; production espera a que termine)
  └── job production (gate production-hetzner)
        ├── state version actual → IP; lee last-success.env (postgres en reuso)
        ├── evidencia: escaneo previo del backend; escaneo aquí del postgres en reuso
        ├── frescura: el commit es la punta de main
        ├── deploy.yml -e backend_image_ref=<build> -e postgres_image_ref=<último éxito>
        │     ├── pull por digest → image.env exacto
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
        │     ├── lee KC_BACKEND_CLIENT_SECRET existente → preserva
        │     ├── copia archivos (sin cambios → no notifica handler)
        │     ├── genera .env (sin cambios → no notifica handler)
        │     └── docker_compose_v2 state: present → no-op
        └── init_keycloak.yml
              └── marker existe → skip completo
```
