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
│   └── deploy.yml                       # Rollout de nueva imagen
└── roles/
    ├── common/                          # Paquetes base y timezone
    ├── docker_host/                     # Instalación y configuración de Docker
    ├── hardening/                       # Hardening del servidor
    └── app/                             # Gestión del stack de la aplicación
        ├── tasks/
        │   ├── main.yml                 # Punto de entrada (importa setup.yml)
        │   ├── setup.yml                # Copia archivos, genera .env, levanta stack
        │   ├── init_keycloak.yml        # Bootstrap de identidad (solo una vez)
        │   └── deploy.yml               # Rollout de nueva imagen
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

### `deploy.yml` — rollout de nueva imagen

Solo actualiza la imagen del backend. No reconfigura el servidor ni toca Keycloak. Requiere `image_tag` como variable extra.

```bash
ansible-playbook \
  -i inventories/prod \
  -i /tmp/hosts.runtime.yml \
  -e "image_tag=abc123def456" \
  playbooks/deploy.yml
```

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

Rollout de nueva imagen. Pasos:

1. Lee `KC_BACKEND_CLIENT_SECRET` del `.env` existente (igual que `setup.yml`)
2. Actualiza `image.env` con el nuevo `IMAGE_TAG`
3. Regenera `.env` (preservando `app_kc_backend_secret`)
4. `docker_compose_v2 state: present pull: always` — pull y deploy en un solo paso

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

3. Agregar al paso de Ansible en el workflow (`_deploy-server.yml`):

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

## Flujo completo — primer deploy

```
GitHub Actions (workflow)
  │
  ├── Terraform apply → obtiene IP del servidor
  ├── Genera hosts.runtime.yml con la IP
  └── Corre: ansible-playbook converge.yml
        │
        ├── common     → timezone, paquetes base
        ├── docker_host → Docker CE instalado y corriendo
        ├── hardening  → fail2ban, sysctl
        ├── setup.yml
        │     ├── genera KC_BACKEND_CLIENT_SECRET (nuevo)
        │     ├── copia compose files + keycloak/ + postgres/
        │     ├── genera .env
        │     └── levanta stack (DB + Keycloak + backend + Traefik)
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
GitHub Actions (workflow)
  │
  ├── CI build → imagen multi-arch → push a GHCR → IMAGE_TAG=abc123
  └── Corre: ansible-playbook deploy.yml -e "image_tag=abc123"
        │
        └── deploy.yml
              ├── lee KC_BACKEND_CLIENT_SECRET del .env existente
              ├── actualiza image.env con IMAGE_TAG=abc123
              ├── regenera .env (preservando el secret)
              └── docker_compose_v2 pull: always → pull + deploy
```

## Flujo completo — converge posterior (sin cambios de imagen)

```
GitHub Actions (workflow — host_changed=true o server_replaced=true)
  │
  └── Corre: ansible-playbook converge.yml
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
