# Métricas: Prometheus, Grafana y Alloy

## Propósito

Instalar, operar, verificar y retirar el stack privado de métricas de Piedra Azul: Prometheus
recolecta las métricas privadas de backend, Keycloak y Traefik y las del host (Alloy), evalúa las
reglas de alerta y Grafana las muestra en dos dashboards. Es un proyecto Compose propio
(`piedrazul-obs`) que la aplicación no necesita para arrancar ni para quedar healthy.

Los productores y sus contratos están en
[Health y métricas](../../architecture/overview.md#health-y-métricas). El stack no recolecta logs
ni trazas, no tiene acceso público y **no envía notificaciones de alertas**.

## Arquitectura

```
                 red de la aplicación (piedrazul_net, externa)
   backend:8081/actuator/prometheus   keycloak:9000/metrics   traefik:8082/metrics
                              ▲
                              │ lectura cada 30 s
                         ┌────┴──────┐        obs_net (propia de piedrazul-obs)
   alloy:12345 ◀─────────┤ prometheus├────────────────▶ grafana :3000
   (host: /proc, /sys, / │  :9090    │                     │
    de solo lectura)     └───────────┘           127.0.0.1:3000 del host
```

- **Prometheus** es el único servicio del stack en la red de la aplicación; lee cada productor por
  el nombre de su contenedor. Sin puerto publicado. Evalúa las reglas de
  `prometheus/rules/alerts.yml`. Sin Alertmanager ni API de administración.
- **Alloy** solo ejecuta `prometheus.exporter.unix` (CPU, memoria, carga, presión PSI, discos y
  sistemas de archivos) sobre `/proc`, `/sys` y `/` del host montados de solo lectura. Corre como
  `nobody`, sin capacidades, sin red ni PID del host, sin socket de Docker. No recolecta logs ni
  métricas de red del host (el contenedor tiene su propia red y reportaría sus interfaces).
- **Grafana** usa una sola fuente de datos (`uid: prometheus`) y los dashboards de
  `grafana/dashboards/`, provisionados de solo lectura. Exige usuario y contraseña (sin acceso
  anónimo ni registro). Tiene deshabilitados la telemetría, los avisos de versión, las noticias y
  la descarga de plugins; su red no restringe otras conexiones salientes. Las alertas de Grafana
  están deshabilitadas: las evalúa Prometheus.
- Los tres corren con raíz de solo lectura, `cap_drop: ALL`, `no-new-privileges` y límites de CPU y
  memoria. Ninguno depende de la aplicación para arrancar: si un productor no existe, su destino
  queda `up = 0`.

### Etiquetas

`job` (backend, keycloak, traefik, host, prometheus) e `instance` (destino fijo). El stack no agrega
etiquetas; las de los productores ya están acotadas: rutas del backend y Keycloak por plantilla,
Traefik por entrypoint y servicio. En el host solo entran sistemas de archivos reales: se excluyen
pseudo-fs, overlays y montajes de contenedores (cada uno sería un `mountpoint` nuevo).

## Archivos

| Archivo | Contenido |
|---|---|
| `infra/observability/compose.yml` | proyecto `piedrazul-obs`: servicios, redes, volúmenes, límites |
| `infra/observability/.env.example` | variables (`GRAFANA_ADMIN_PASSWORD` obligatoria; red, puerto y retención opcionales) |
| `infra/observability/prometheus/` | destinos (`prometheus.yml`) y reglas (`rules/*.yml`) |
| `infra/observability/alloy/config.alloy` | exportador del host |
| `infra/observability/grafana/provisioning/` | fuente de datos y proveedor de dashboards |
| `infra/observability/grafana/dashboards/` | `plataforma.json`, `backend-jvm.json` |
| `infra/observability/tests/alerts.test.yml` | pruebas unitarias de las reglas |
| `infra/ansible/roles/observability/`, `infra/ansible/playbooks/observability.yml` | instalación en un host |
| `infra/tests/metrics-stack-check.sh` | verificación local de configuración y ejecución |

Prometheus, Alloy y Grafana montan sus directorios de configuración, no archivos sueltos: una
escritura atómica (Ansible, un editor) reemplaza el archivo y un montaje de archivo seguiría viendo
el anterior.

## Recursos y almacenamiento

| Servicio | Límite de memoria | Límite de CPU | Volumen |
|---|---|---|---|
| prometheus | 512 MiB | 0,50 | `prometheus_data` (TSDB) |
| grafana | 512 MiB | 0,50 | `grafana_data` (base SQLite: usuarios, preferencias, dashboards propios) |
| alloy | 192 MiB | 0,25 | ninguno (tmpfs; sin WAL porque no reenvía) |

- **Retención**: `PROMETHEUS_RETENTION_TIME` (30d) y `PROMETHEUS_RETENTION_SIZE` (2GB); se aplica la
  primera que se cumpla. Para cumplir el tamaño Prometheus solo borra bloques persistidos: el WAL,
  el head y una compactación en curso pueden llevar el volumen por encima de 2 GB. No es un tope
  del disco; uno duro exigiría un sistema de archivos de tamaño fijo para el volumen.
- **Crecimiento**: con un backend, Keycloak, Traefik y un host hay unas 3 200–3 400 series activas
  (medido localmente). A 30 s son ≈10 M muestras/día; con 1–2 bytes por muestra comprimida,
  ≈10–20 MB/día y ≈300–600 MB en 30 días, más el WAL. Una etiqueta nueva de alta cardinalidad
  cambia esto: el panel **Series por destino** lo muestra.
- **Medición local indicativa** (Docker Desktop/WSL2, minutos de ejecución, tráfico sintético):
  Prometheus ≈35–45 MiB, Alloy ≈35–40 MiB, Grafana ≈210–230 MiB, CPU < 1 %. No describe el consumo
  en un VPS (ver [Mediciones locales](#mediciones-locales)).

## Operación local

Con la aplicación local en marcha (crea `piedrazul_net`):

```bash
cd infra/observability
cp .env.example .env    # y fijar GRAFANA_ADMIN_PASSWORD
docker compose up -d --wait
```

Grafana queda en `http://127.0.0.1:3000` (usuario `admin`). La aplicación de desarrollo
(`docker-compose.yml` raíz) solo levanta postgres y Keycloak: los destinos backend y traefik quedan
`up = 0` mientras no existan contenedores con esos nombres en la red.

Prometheus no está publicado. Para consultarlo:

```bash
docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/targets'
```

Verificación completa (no usa la aplicación local: levanta sus propios productores con nombres de
proyecto y red únicos y los elimina al terminar):

```bash
infra/tests/metrics-stack-check.sh
```

## Instalación con Ansible

`playbooks/observability.yml` instala o actualiza el stack en `/opt/piedrazul-obs`. No forma parte
del workflow de producción ni de `converge.yml`/`deploy.yml`, y no modifica la aplicación.

Desde la raíz del repositorio, con la misma convención que el workflow de producción: `ANSIBLE_CONFIG`
apunta a `infra/ansible/ansible.cfg` (sin él, desde la raíz no se carga y se pierden
`remote_user = ansible`, `become` y la verificación de la clave del host), las colecciones de
`requirements.yml` en `infra/ansible/collections` y un inventario de ejecución que solo da la
dirección del host `vps`:

```bash
export ANSIBLE_CONFIG=infra/ansible/ansible.cfg
ansible-galaxy collection install -r infra/ansible/requirements.yml -p infra/ansible/collections
printf 'all:\n  hosts:\n    vps:\n      ansible_host: %s\n' '<IP del servidor>' > /tmp/hosts.runtime.yml
ansible-playbook \
  -i infra/ansible/inventories/prod \
  -i /tmp/hosts.runtime.yml \
  infra/ansible/playbooks/observability.yml
```

Requisitos: la clave SSH del usuario `ansible` y la clave del host ya confiada en `known_hosts`
(`host_key_checking = True`); Docker con el plugin Compose y la aplicación instalada (red
`piedrazul_net`). Si falta Docker o la red, el rol falla sin copiar ni levantar nada.

Las imágenes del stack no pasan por el escaneo ni la política de vulnerabilidades de la cadena de
producción. Antes de instalar o de cambiar una de ellas, escanear su manifiesto `linux/amd64` con
el mismo Trivy y las mismas opciones que `.github/actions/trivy-scan` y decidir con los criterios de
[la política de vulnerabilidades](politica-vulnerabilidades-imagenes.md).

El rol:

1. Comprueba Docker y la red de la aplicación.
2. Copia `compose.yml` y los directorios de configuración (`prometheus/`, `alloy/`,
   `grafana/provisioning/`, `grafana/dashboards/`), del usuario `ansible`, legibles por los
   contenedores; no contienen secretos.
3. Borra de esos directorios lo que el repositorio ya no tiene (ver
   [Cambios y retiro de configuración](#cambios-y-retiro-de-configuración)).
4. Crea `/opt/piedrazul-obs/.env` **solo si no existe** (root, 0600), con la contraseña del
   administrador de Grafana: 32 caracteres alfanuméricos aleatorios que genera el controlador de
   Ansible (el runner o el equipo del operador). Allí solo existe durante la tarea (Ansible la
   escribe en un archivo temporal que borra al terminar); viaja al host por la conexión SSH solo
   cuando `.env` no existe y no aparece en la salida (`no_log`). Un run posterior no la regenera
   ni la reescribe.
5. Valida y levanta el proyecto, reinicia los servicios cuya configuración cambió y verifica que los
   cinco destinos se leen y que los dos dashboards están cargados.

Repetirlo sin cambios en el repositorio no cambia nada.

### Cambios y retiro de configuración

Los directorios de configuración de `/opt/piedrazul-obs` son del repositorio: el rol los deja
iguales a `infra/observability/` (agrega, actualiza y borra archivos). No se guardan allí archivos
propios. `.env`, `compose.yml` y los volúmenes no entran en esa reconciliación.

| Cambio en el repositorio | Efecto al repetir el playbook |
|---|---|
| regla en `prometheus/rules/` agregada, cambiada o borrada; `prometheus.yml` | reinicio de Prometheus; una regla borrada deja de evaluarse |
| `alloy/config.alloy` | reinicio de Alloy |
| `grafana/provisioning/` (fuente de datos, proveedor) | reinicio de Grafana |
| dashboard en `grafana/dashboards/` agregado, cambiado o borrado | sin reinicio: Grafana lo recarga en ≤ 60 s y un archivo borrado retira su dashboard |
| `compose.yml` | Compose recrea los servicios afectados |

Lo que no es configuración del repositorio no se toca: el histórico de Prometheus, y en Grafana los
usuarios, las preferencias y los dashboards creados desde la interfaz fuera de la carpeta
**Piedra Azul**. Los dashboards provisionados no se pueden borrar ni guardar desde la interfaz.

Sin Ansible (local), los mismos efectos se consiguen borrando el archivo y reiniciando el servicio
correspondiente (`docker compose restart prometheus`, `alloy` o `grafana`).

### Acceso a Grafana

Grafana escucha solo en `127.0.0.1:3000` del host; es el único acceso implementado. Por túnel SSH:

```bash
ssh -L 3000:127.0.0.1:3000 ansible@<host>
```

La contraseña está en `/opt/piedrazul-obs/.env` (`sudo`). Grafana la aplica solo al crear su base;
para cambiarla después:

```bash
cd /opt/piedrazul-obs
sudo docker compose exec grafana grafana cli admin reset-admin-password '<nueva>'
```

y reflejarla en `.env`.

## Alertas

Prometheus evalúa las reglas cada 30 s. **No hay notificaciones externas configuradas**: sin
Alertmanager ni punto de contacto, una alerta solo se ve en el dashboard Plataforma (estadística
**Alertas disparadas** y tabla de alertas) y en la serie `ALERTS`. Con Prometheus detenido no se
evalúa ninguna regla.

| Alerta | Fuente | Condición | Duración | Ausencia de datos |
|---|---|---|---|---|
| `PiedrazulTargetDown` (critical) | `up` | backend, keycloak, traefik o host no se pueden leer | 5 m | el destino es estático: caído = `up 0`, nunca ausente |
| `PiedrazulHttp5xxRatioHigh` (warning) | `traefik_service_requests_total` | > 5 % de 5xx por servicio y > 0,02 req/s | 10 m | sin tráfico no se evalúa; Traefik caído lo cubre `PiedrazulTargetDown` |
| `PiedrazulHostRootFilesystemLow` (warning) | `node_filesystem_*{mountpoint="/"}` | libre en `/` < max(15 % de la capacidad, 10 GiB) | 15 m | Alloy caído: `PiedrazulTargetDown{job="host"}`; `/` sin reportar: la siguiente |
| `PiedrazulHostRootFilesystemMissing` (warning) | `up`, `node_filesystem_size_bytes` | Alloy responde pero no reporta `/` | 15 m | — |
| `PiedrazulHostMemoryLow` (warning) | `node_memory_*` | MemAvailable < 10 % | 10 m | Alloy caído: `PiedrazulTargetDown{job="host"}` |
| `PiedrazulRuleEvaluationFailing` (warning) | `prometheus_rule_evaluation_failures_total` | alguna evaluación falló en 10 m | — | una regla rota no queda como "sana" |
| `PiedrazulPrometheusConfigReloadFailed` (warning) | `prometheus_config_last_reload_successful` | recarga fallida | 5 m | — |

En la raíz, el piso de 10 GiB domina en discos de hasta ≈67 GiB y el 15 % en los mayores. El panel
**Raíz del host: espacio libre y umbral** muestra los dos valores.

`tests/alerts.test.yml` cubre el caso normal, la falla y la ausencia de datos de cada regla (en la
raíz, las dos ramas del umbral). `metrics-stack-check.sh` además detiene el backend y comprueba la
transición real a `pending` (y a `firing` con `ALERT_FIRING=1`) y su resolución.

## Reinicio, recuperación y retirada

| Operación | Comando (en `infra/observability` o `/opt/piedrazul-obs`) | Efecto en la aplicación |
|---|---|---|
| Reiniciar | `docker compose restart` | ninguno |
| Detener | `docker compose stop` | ninguno |
| Retirar conservando datos | `docker compose down` | ninguno; volúmenes intactos |
| Volver a levantar | `docker compose up -d --wait` | ninguno; TSDB y Grafana conservan su estado |
| Borrar todo el estado | `docker compose down -v` | ninguno; se pierden el histórico y la base de Grafana (en el host, `.env` sigue y la contraseña se reaplica al recrear la base) |
| Volver a una versión anterior | checkout de la versión y repetir el playbook | ninguno; la configuración queda igual a esa versión |

La aplicación no depende del stack: detenerlo o borrarlo no cambia su health ni sus rutas.
Prometheus solo se une a `piedrazul_net`; no crea ni modifica la red.

## Problemas frecuentes

| Síntoma | Causa probable | Acción |
|---|---|---|
| `up` falla con `network piedrazul_net declared as external, but could not be found` | la aplicación no está instalada o levantada | levantar la aplicación primero |
| `GRAFANA_ADMIN_PASSWORD es requerido` | falta `.env` | crearlo desde `.env.example` (local) o repetir el playbook (host) |
| destino `down` con `no such host` | el contenedor del productor no existe en la red | `docker ps`; levantar el servicio |
| destino `down` con `connection refused` | el productor corre sin su listener de métricas | revisar `MANAGEMENT_SERVER_PORT`, `KC_METRICS_ENABLED` o los flags de Traefik |
| paneles con **SIN DATOS** | no hay serie: destino caído o Prometheus sin datos para ese rango | revisar **Estado de la recolección** |
| `PiedrazulHostRootFilesystemMissing` | `/` no está montado en Alloy o lo excluye `config.alloy` | revisar el montaje `/:/host/root` y `mount_points_exclude` |
| Alloy se reinicia en bucle | error en `config.alloy` | `docker compose logs alloy` |
| cambio en `prometheus.yml`, las reglas, `config.alloy` o el provisioning sin efecto | se leen solo al arrancar | reiniciar el servicio; el playbook lo hace solo |
| contraseña de `.env` no funciona | la base de Grafana ya existía | `grafana cli admin reset-admin-password` (arriba) |

## Mediciones locales

En Docker Desktop/WSL2, Alloy observa la VM Linux compartida de WSL2 (kernel
`*-microsoft-standard-WSL2`), no Windows ni un VPS: CPU, memoria y carga son las de esa VM; los
sistemas de archivos, los de la distribución desde la que se invoca Docker. Esos valores sirven para
verificar la integración, no para dimensionar un servidor.
