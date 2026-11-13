# Logs: journald, Alloy, Loki y Grafana

## Propósito

Instalar, operar, verificar y retirar la recolección privada de logs de Piedra Azul: los
contenedores seleccionados escriben en el journal del host con el driver `journald` de Docker,
Alloy lee ese journal y envía solo las fuentes admitidas a Loki, y Grafana las muestra en el
dashboard **Logs**. Forma parte del proyecto Compose `piedrazul-obs` (ver
[Métricas](metricas.md)); la aplicación no lo necesita para arrancar ni para quedar healthy.

La entrega es de **mejor esfuerzo**: una línea puede faltar o repetirse (ver
[Pérdida, duplicación y retención](#pérdida-duplicación-y-retención)). Loki no tiene acceso
público ni puerto en el host.

## Arquitectura

```
 contenedor seleccionado ── driver journald (non-blocking) ──▶ journal del host
                                                              /var/log/journal (persistente)
                                                                     │ lectura (solo lectura,
                                                                     │ grupo del journal)
 obs_net (piedrazul-obs)                                             ▼
   grafana :3000 ──────────▶ loki :3100 ◀── envío ─────────────── alloy
   (127.0.0.1 del host)      (sin puerto)   (allowlist,            ├ posiciones: volumen alloy_data
                                ▲            etiquetas acotadas)   └ métricas del host
                   prometheus ──┘ métricas de loki y de alloy
```

- **Driver journald.** Cada servicio que se activa escribe por el driver `journald` en modo
  `non-blocking` (buffer en memoria, ver [Activación de una fuente](#activación-de-una-fuente)).
  Docker no cambia de driver por defecto: el `daemon.json` del host sigue en `json-file` y cada
  servicio lo elige en su archivo Compose.
- **Journal.** Persistente y acotado por el drop-in del rol `observability`
  (`/etc/systemd/journald.conf.d/60-piedrazul-obs.conf`): 2 GiB en total, archivos de 128 MiB,
  7 días. Los límites son de **todo el journal del host**, no solo de Piedra Azul.
- **Alloy** (el mismo contenedor que exporta las métricas del host) lee el journal por el montaje
  de solo lectura de `/` (`/host/root/var/log/journal`) como `nobody`, con el grupo dueño de los
  archivos del journal como grupo suplementario (`OBS_JOURNAL_GID`). Sin socket de Docker, sin
  root, sin capacidades. Selecciona, etiqueta y envía a `loki:3100` por `obs_net`.
- **Loki** (un proceso, almacenamiento en el sistema de archivos del volumen `loki_data`) recibe
  solo de Alloy y responde solo a Grafana y Prometheus. Sin autenticación propia: su única
  protección es no estar publicado ni en la red de la aplicación.
- **Grafana** tiene la fuente de datos `loki` (no por defecto) y el dashboard de logs.
- **Prometheus** lee las métricas de Loki (`job="loki"`) y las propias de Alloy (`job="alloy"`,
  endpoint global `/metrics`; distinto de `job="host"`, que es el exportador del host) y evalúa
  las reglas de `prometheus/rules/logs.yml`.

## Política de fuentes

| Fuente | Estado | En operación | [Perfil de laboratorio sintético](#perfil-de-laboratorio-sintético) |
|---|---|---|---|
| Traefik | admitida | activada y seleccionada por defecto | sí |
| Avisos de journald por límite de tasa de `docker.service` | admitidos | se envían siempre; son la evidencia de líneas suprimidas | sí |
| Backend | condicionada | cuando figura en `obs_log_privacy_controls_done` ([Adopción en operación](#adopción-en-operación)) | sí, sin esa decisión |
| Keycloak | condicionada | ídem | sí, sin esa decisión |
| PostgreSQL | condicionada | ídem | sí, sin esa decisión |
| Migraciones, el propio stack, otras unidades del host | excluidas | nunca | no |

`obs_log_privacy_controls_done` es una decisión humana, no una casilla técnica: que los logs de
ese servicio son aceptables con datos reales, con la evidencia del laboratorio y los cambios del
productor que haga falta. Lo que hay que decidir por servicio está en
[Adopción en operación](#adopción-en-operación). Mientras no se tome, sus logs pueden llevar datos
personales y no se envían.

Una sola política (`roles/observability/tasks/log_policy.yml`) y una sola lista
(`obs_log_services`, inventario), aplicadas en dos playbooks, más el filtro de Alloy:

0. **converge** (`roles/app/tasks/setup.yml`, `include_role` de la política): decide qué
   contenedores escriben en el journal (driver `journald`), que es la activación con efecto de
   privacidad. Falla antes de copiar Compose o escribir `.env` si la lista incluye un servicio
   fuera de lo admitido.
1. **Alloy** (`alloy/config.alloy`, `loki.relabel "select"`) solo deja pasar entradas escritas
   por dockerd del proyecto `OBS_LOG_PROJECT` cuyo servicio coincide con `OBS_LOG_SERVICES`, y
   los avisos de journald de mensajes suprimidos de `docker.service` (ver
   [Procedencia](#procedencia-de-las-entradas)). Todo lo demás se descarta dentro de Alloy.
2. **observability** (`logs_preflight.yml`, la misma política) falla sin tocar el host si la
   lista incluye un servicio excluido, uno condicionado que no figura en
   `obs_log_privacy_controls_done` (salvo con el perfil de laboratorio) o algo que no es un
   nombre de servicio.

El filtro de Alloy **no limpia el journal**: lo que un contenedor escribe con el driver journald
queda en el journal del host (hasta 7 días o 2 GiB) aunque no se envíe a Loki, y lo pueden leer
root y los miembros del grupo del journal. Por eso una fuente condicionada no se activa en el
journal antes de que sus controles estén completos, y lo escrito antes de un control
(registros PRE) no desaparece después: ni del journal ni, si ya se envió, de Loki hasta su
retención.

### Procedencia de las entradas

En una entrada del journal solo los campos que empiezan por `_` los pone journald, a partir de
las credenciales del proceso que escribe; los demás los elige el propio cliente. Cualquier
proceso del host puede escribir en el journal (`logger --journald`, `systemd-cat`,
`sd_journal_send`) una entrada con `COM_DOCKER_COMPOSE_PROJECT=piedrazul`,
`COM_DOCKER_COMPOSE_SERVICE=traefik`, `MESSAGE_ID` u `OBJECT_SYSTEMD_UNIT`. Esos campos no
prueban que la entrada venga de Docker.

| Fuente | Se exige (campos que pone journald) | Campos del cliente que se usan después |
|---|---|---|
| Contenedor | `_TRANSPORT=journal`, `_SYSTEMD_UNIT=docker.service`, `_COMM=dockerd`, `_UID=0` | `COM_DOCKER_COMPOSE_PROJECT`, `COM_DOCKER_COMPOSE_SERVICE`, `PRIORITY`, `CONTAINER_ID` (los copia dockerd) |
| Aviso de supresión | `_TRANSPORT=driver` (solo journald) y `OBJECT_SYSTEMD_UNIT=docker.service` (unidad del proceso suprimido) | `MESSAGE_ID` (en `_TRANSPORT=driver` también lo pone journald) |

Lo que no trae esos campos se descarta: con `_SYSTEMD_UNIT` ausente (journald sin información
de cgroup) o un aviso sin `OBJECT_SYSTEMD_UNIT`, no se envía nada. El texto del mensaje nunca
decide.

Límites:

- Dentro de lo que escribe dockerd, el proyecto y el servicio son las etiquetas del contenedor:
  quien puede crear contenedores en ese Docker (grupo `docker` o root, equivalentes a root en el
  host) puede ponerle `com.docker.compose.project=piedrazul`. Esa frontera es la de Docker.
- Docker sin root (rootless), otro nombre de proceso o de unidad dejarían todo fuera: la
  selección falla cerrada.
- Los valores reales de esos campos en el servidor (incluido `OBJECT_SYSTEMD_UNIT` en los avisos
  de supresión) solo los prueba `infra/tests/logs-e2e-lab.sh`; la verificación local los
  supone a partir de journals sintéticos.

## Activación de una fuente

Seleccionar un servicio (`obs_log_services`) no hace que escriba en el journal; activarlo en el
journal no lo envía si no está seleccionado. Activar una fuente requiere las dos cosas:

1. **Driver en el productor.** En `infra/compose/prod.yml` traefik, keycloak, backend y
   postgres extienden (`extends`) una de las dos bases de `infra/compose/logging.yml`, la que
   indica `.env` (`<SERVICIO>_LOGGING`):

   - `journald`: driver `journald` con

     | Opción | Valor | Para qué |
     |---|---|---|
     | `mode` / `max-buffer-size` | `non-blocking` / `4m` | si el buffer se llena Docker descarta líneas, nunca bloquea al servicio por el journal |
     | `labels` | `com.docker.compose.project,com.docker.compose.service` | campos `COM_DOCKER_COMPOSE_*` que usa la selección |
     | `tag` | `{{.Name}}` | `SYSLOG_IDENTIFIER` = nombre del contenedor (`journalctl -t backend`) |

   - `default`: **ninguna** configuración propia. El contenedor usa el driver, las opciones y la
     rotación de `daemon.json` (`json-file`, 10 MiB × 3, modo `blocking` por defecto de Docker),
     exactamente como antes de este cambio: mismo hash de Compose, sin recreación, sin modo
     non-blocking ni buffer. Es también lo que da un `.env` sin la variable (desarrollo,
     harnesses, `.env` anteriores). Un valor desconocido hace fallar Compose.

     Esos valores los toma el contenedor **al crearse** (`HostConfig.LogConfig`). El role
     `docker_host` deja el daemon reiniciado y con `daemon.json` en vigor antes de crear ninguno, y
     en producción la identidad en ejecución verifica la configuración efectiva (`journald` para
     `obs_log_services`; para el resto, el driver y las `log-opts` de `daemon.json`, sin repetirlas
     en Compose). Un contenedor creado antes no la hereda ni Compose lo recrea: se corrige una vez,
     con autorización, según [reconciliar-logs-contenedores.md](reconciliar-logs-contenedores.md).

   `env.j2` escribe `journald` para los servicios de `obs_log_services` (inventario; por defecto
   `[traefik]`) y `default` para el resto. Todas las llamadas de Compose del rol `app` usan
   `prod.yml` con el mismo `.env`, así que todas ven la misma configuración: un run repetido no
   recrea nada y cambiar el modo de un servicio lo recrea una vez, en la siguiente llamada que
   lo levanta (converge para traefik y postgres, `keycloak_lifecycle.yml` para keycloak, deploy
   o `refresh_backend.yml` para el backend). Con `journald`, `docker logs` sigue funcionando
   (lee del journal). `daemon.json` no cambia.

   En un host sin journald (Docker Desktop) un contenedor con `journald` no arranca
   (`journald is not enabled on this host`): falla visible, nunca descarte silencioso.
   Desarrollo (`docker-compose.yml`) no incluye `prod.yml`.
2. **Selección.** La misma lista `obs_log_services`: repetir `playbooks/observability.yml`.

Con la configuración por defecto solo Traefik va al journal y a Loki.

## Perfil de laboratorio sintético

Para un host de laboratorio desechable con **identidades y datos sintéticos** el perfil
`infra/ansible/profiles/logs-lab-synthetic.yml` lleva al journal y a Loki los cuatro servicios
principales con su diagnóstico completo, sin recortar mensajes:

| Servicio | Qué llega | Qué no se activa |
|---|---|---|
| traefik | errores de rutas, TLS, ACME, proveedor de archivos y arranque (nivel ERROR por defecto) | access log (IP, rutas, consultas) |
| keycloak | arranque, base de datos, configuración, correo, proveedores; eventos `LOGIN_ERROR` del listener `jboss-logging` (WARN: usuario intentado, IP, cliente, error). `LOGIN` correcto es DEBUG y no aparece | más nivel de consola; el almacén de eventos y `LoginAuditJob` (API de administración) no dependen de la consola |
| backend | mensajes, clases de excepción, trazas completas con causas, SQLState, `correlation_id` | SQL (`org.hibernate.SQL`) y valores de parámetros (`org.hibernate.orm.jdbc.bind`) siguen en WARN |
| postgres | arranque/parada, recuperación, checkpoints, autenticación fallida, esperas de lock (`log_lock_waits`), errores con SQLSTATE, `DETAIL` y la sentencia (con `$n`) en una entrada, `rol@base` en cada línea de sesión | `log_statement`, `log_min_duration_statement`, `log_connections`, valores de parámetros (`log_parameter_max_length_on_error=0`) |

El perfil valida, con datos sintéticos, la misma configuración que usará la operación: la misma
lista, las mismas bases de `logging.yml`, la misma canalización. No es otra arquitectura. Lo que
**no** es: no toma la decisión de privacidad (`obs_log_privacy_controls_done` no cambia), no
admite backend, keycloak ni postgres para datos clínicos reales y no se guarda en el host. Sus logs pueden llevar identificadores de personas
(sintéticas), valores de error (`Key (document_number)=(…)`), cuerpos de error de Keycloak y
nombres de usuario de intentos de inicio de sesión. Con datos reales no se usa.

**Autorización.** `logs_lab_authorization: synthetic-data-only` (valor exacto) en el mismo run.
Sin ella, o con otro valor, converge y observability rechazan backend, keycloak y postgres
(sin decisión de privacidad) antes de cambiar nada. migrate, el propio stack y cualquier otro nombre se rechazan
aunque esté.

**Activación.** Pasar el perfil a los tres playbooks, siempre:

```bash
ansible-playbook -i <inventario> -e @profiles/logs-lab-synthetic.yml playbooks/converge.yml
```

```bash
ansible-playbook -i <inventario> -e @profiles/logs-lab-synthetic.yml playbooks/deploy.yml
```

```bash
ansible-playbook -i <inventario> -e @profiles/logs-lab-synthetic.yml playbooks/observability.yml
```

converge escribe `.env` con `journald` en los cuatro y recrea postgres y keycloak; traefik ya
está en `journald` por defecto y no cambia. El backend se recrea en su siguiente arranque
(deploy o `refresh_backend.yml`). deploy no
reescribe `.env` (usa el de converge): el perfil no le cambia nada y se pasa igual para no
depender del orden. Sin el perfil, cada playbook vuelve a la lista del inventario **solo en su
lado**:

- observability sin el perfil cambia **solo la selección de Alloy**: Loki deja de recibir
  keycloak, backend y postgres, pero sus contenedores siguen corriendo con `journald` y siguen
  escribiendo en el journal del host (7 días / 2 GiB), que no se envía pero tampoco se limpia;
- converge sin el perfil reescribe `.env` con `default` para keycloak, backend y postgres y
  recrea postgres (converge) y keycloak (`keycloak_lifecycle.yml`); traefik sigue en `journald`
  (con y sin el perfil) y no se recrea; el **backend** solo cambia de driver cuando se recrea
  (deploy o `refresh_backend.yml`).

Desactivar la recolección del laboratorio es, por eso, los dos lados (ver **Reversión**).

**Inspección.**

```bash
# Driver y opciones efectivos de cada contenedor
for c in traefik keycloak backend postgres; do printf '%s ' "$c"; sudo docker inspect -f '{{json .HostConfig.LogConfig}}' "$c"; done
```

```bash
# Valores que dio Ansible (en /opt/piedrazul)
sudo grep -E '_LOGGING=|laboratorio' /opt/piedrazul/.env
```

```bash
# Campos de procedencia de una entrada real (deben ser docker.service, dockerd, 0)
journalctl -o json -n 1 COM_DOCKER_COMPOSE_SERVICE=backend | jq '{_TRANSPORT, _SYSTEMD_UNIT, _COMM, _UID, COM_DOCKER_COMPOSE_PROJECT, COM_DOCKER_COMPOSE_SERVICE}'
```

```bash
# Selección efectiva de Alloy (en /opt/piedrazul-obs)
sudo grep -E '^OBS_LOG_(PROJECT|SERVICES)=' /opt/piedrazul-obs/.env
```

**Consultas** (Explore o dashboard **Logs**, fuente `loki`):

| Para | LogQL |
|---|---|
| un servicio | `{service="postgres"}` |
| errores de todos | `{service=~".+", level=~"error\|fatal"}` |
| una petición en todos los servicios | `{service=~".+"} \|= "<correlation_id>"` o `{service="backend"} \| correlation_id="<id>"` |
| errores de SQL de la aplicación | `{service="postgres", level="error"} \|= "piedrazul_app@"` |
| inicios de sesión fallidos | `{service="keycloak"} \|= "LOGIN_ERROR"` |
| por tiempo | el selector de rango de Grafana; en API, `start`/`end` |

**Revisión de contenido.** El canal no filtra contenido: lo que el productor escribe llega. Antes
de dar por buena una sesión de laboratorio, revisar con

```text
{service=~".+"} |~ "(?i)(password|passwd|secret|token|bearer|authorization|cookie)[\"]?\\s*[=:]"
```

Un resultado con un valor real (no sintético) es un hallazgo del productor: se corrige en su
origen y lo ya escrito sigue en el journal (7 días) y en Loki (14 días) hasta su retención, o se
borra con la retirada y `obs_logs_purge=true`. `logs-stack-check.sh` comprueba que esta consulta
encuentra un marcador sintético.

**Riesgos conocidos que el perfil acepta** (datos sintéticos):

- Backend: `ex.getMessage()` y valores de la petición en varios manejadores de excepciones,
  cuerpos de error de Keycloak (`KeycloakUserClient`), la excepción completa en el 5xx de
  pacientes y el `Detail: Key (…)=(…)` de pgJDBC: llevan los valores que el usuario envió.
- Keycloak: `LOGIN_ERROR` con el nombre de usuario intentado y la IP.
- PostgreSQL: `DETAIL` con valores de claves y la sentencia que falló. Los clientes locales del
  contenedor (reconciliación de roles con contraseñas, `psql` de un operador) corren con
  `PGOPTIONS=-c log_min_error_statement=panic`: una sentencia suya que falla no se registra con
  su texto. Un error de sintaxis de `psql` en la primera inicialización puede mostrar el
  fragmento de la sentencia en la salida del contenedor (las variables de `psql` van citadas y
  no rompen la sintaxis).
- Las trazas de más de 256 líneas se parten en varias entradas.

Cerrar esos puntos es trabajo del productor (backend, Keycloak), no de la canalización, y es
condición para admitirlos fuera del laboratorio (`obs_log_privacy_controls_done`).

**Reversión.** Quitar la recolección de laboratorio tiene dos lados independientes, y
observability solo hace uno:

1. **Productores (lo que llega al journal).** converge **sin** el perfil: `.env` vuelve a
   `TRAEFIK_LOGGING=journald` y `default` en los otros tres; recrea postgres y keycloak.
   Después, deploy (o `refresh_backend.yml`) **sin** el perfil para recrear el backend. Hasta
   esa recreación, un contenedor sigue escribiendo en el journal con el driver con que se creó,
   aunque `.env` ya diga otra cosa.
2. **Selección (lo que llega a Loki).** observability **sin** el perfil: Alloy deja de enviar
   keycloak, backend y postgres. Solo esto **no** devuelve ningún contenedor a `default`.

Comprobar los dos:

```bash
for c in traefik keycloak backend postgres; do printf '%s ' "$c"; sudo docker inspect -f '{{.HostConfig.LogConfig.Type}}' "$c"; done
```

```bash
sudo grep -E '_LOGGING=' /opt/piedrazul/.env; sudo grep -E '^OBS_LOG_SERVICES=' /opt/piedrazul-obs/.env
```

Esperado: `journald` solo en traefik (los otros tres con el driver del daemon, `json-file`) y
`OBS_LOG_SERVICES=traefik`. Lo ya escrito queda en el journal hasta su rotación y en Loki hasta
la retención; para borrarlo en un laboratorio: retirada de logs con `-e obs_logs_purge=true` (ver
[Retirada](#retirada)) y `journalctl --rotate --vacuum-time=1s`, o reconstruir el host.

### Verificación en el host de laboratorio

Lo que las pruebas locales no pueden mostrar y se comprueba en el host con el perfil activo:

1. `docker inspect`: `journald` con las opciones del contrato en los cuatro; en migrate y en el
   stack, la configuración del daemon sin opciones propias (`json-file`, `max-size`/`max-file`).
2. `journalctl -o json` de cada servicio: `_SYSTEMD_UNIT=docker.service`, `_COMM=dockerd`,
   `_UID=0`, `COM_DOCKER_COMPOSE_PROJECT=piedrazul` y el servicio.
3. En Loki, entradas reales de los cuatro (`label_values(service)`), con `level` donde
   corresponde; ninguna de `migrate` ni del stack.
4. Diagnóstico provocado con datos sintéticos: un error del backend con traza completa y su
   `correlation_id`, seguido en Traefik/Keycloak/PostgreSQL por el mismo rango de tiempo; un
   error de PostgreSQL (clave duplicada) en una entrada con `DETAIL`, `STATEMENT` y `rol@base`;
   un `LOGIN_ERROR` de Keycloak que también aparece en el almacén de eventos (y que
   `LoginAuditJob` registra); un reinicio de postgres con su secuencia de arranque.
5. La consulta de **Revisión de contenido** sin valores reales; ninguna contraseña de
   `.env` en Loki ni en el journal (`journalctl | grep -F` por cada una, sin imprimirla).
6. Recreación y desactivación: un run de converge con el perfil no recrea nada; observability
   sin el perfil deja de enviar a Loki keycloak, backend y postgres **sin** cambiar su driver
   (`docker inspect` sigue en `journald`); converge sin el perfil recrea postgres y keycloak en
   `default`, y el backend pasa a `default` al recrearse en deploy o `refresh_backend.yml`.
7. Recursos con la topología real: memoria de Alloy y Loki, uso del journal por día y
   reintentos o descartes en el dashboard.

## Ajustes de PostgreSQL

`prod.yml` fija para postgres tres ajustes, con y sin el perfil (no dependen de dónde vayan sus
logs). Se verificaron con PostgreSQL 16 (base de la imagen) y clientes de protocolo extendido
(como pgJDBC: sentencia con `$n` y valores aparte) y de consulta simple:

| Ajuste | Clase | Qué registra | Qué no |
|---|---|---|---|
| `log_line_prefix=%m [%p] %q%u@%d ` | operación normal | `rol@base` en cada línea de sesión: distingue backend, Keycloak, migraciones y operadores en un clúster compartido | — |
| `log_lock_waits=on` | operación normal | esperas de lock de más de `deadlock_timeout` (1 s): `LOG` con los procesos implicados y la sentencia, **también cuando la consulta termina bien** | los valores de un cliente de protocolo extendido: la sentencia lleva `$n` (verificado) |
| `PGOPTIONS=-c log_min_error_statement=panic` (solo clientes `libpq` dentro del contenedor) | protección permanente | — | la sentencia de un error o de una espera de lock de la reconciliación de roles (`ALTER ROLE … PASSWORD`) o de un `psql` por `docker exec` (verificado: el marcador no aparece; sin `PGOPTIONS`, sí) |

Lo demás queda en los valores de PostgreSQL: `log_min_error_statement=error` (la sentencia que
falla, con `$n`, sin parámetros: `log_parameter_max_length_on_error=0`), `log_error_verbosity`
por defecto (`DETAIL` incluido) y sin `log_statement`, `log_min_duration_statement` ni
`log_connections`. Lo que puede llevar valores, en cualquier destino (`json-file` del host o
Loki):

- `DETAIL` de una violación de restricción (`Key (document_number)=(…) already exists`): es el
  comportamiento por defecto de PostgreSQL, anterior a este cambio, y el dato más útil para
  diagnosticar el error.
- La sentencia de un cliente que escribe literales en el SQL (un operador con `psql` por la red;
  no la aplicación ni Keycloak, que usan parámetros), en dos casos:
  - un **error**: comportamiento por defecto de PostgreSQL, anterior a este cambio;
  - una **espera de lock de más de 1 s**: lo agrega `log_lock_waits`, **aunque la consulta
    termine bien** y antes no hubiera dejado ninguna línea. Es exposición adicional, no la misma:
    un `SELECT … WHERE document_number = '…'` correcto que esperó un lock queda registrado con
    su literal (verificado: dos líneas, `still waiting` y `acquired`, ambas con la sentencia).
    Se conserva porque las esperas de lock son una causa frecuente de lentitud y este es el
    único registro que identifica la sentencia y los procesos implicados; el volumen es bajo (solo
    esperas de más de `deadlock_timeout`).

  Los clientes locales del contenedor no registran la sentencia en ninguno de los dos casos
  (`PGOPTIONS`). Para un operador por la red: consultas con parámetros (`\bind` en `psql` 16) o
  desde `docker exec` cuando la consulta lleva datos de personas.

## Adopción en operación

El destino es el mismo que valida el laboratorio: los cuatro servicios en Loki con su diagnóstico.
Pasar de la configuración por defecto a esa operación no cambia ningún archivo: es inventario.

```yaml
# inventories/prod/group_vars/all.yml
obs_log_services: [traefik, keycloak, backend, postgres]
obs_log_privacy_controls_done: [keycloak, backend, postgres]
```

y repetir converge, deploy y observability **sin** el perfil. `logs-stack-check.sh` y
`log-producers-contract-check.sh` prueban que esa combinación se admite y produce exactamente la
misma configuración de Compose que el laboratorio.

Añadir un servicio a `obs_log_privacy_controls_done` es la decisión, por servicio, de que sus
logs son aceptables con datos reales en el journal del host (7 días) y en Loki (14 días), con el
acceso a Grafana como única vía de lectura. El acceso restringido no autoriza a guardar
credenciales; la decisión se toma con la evidencia del laboratorio (sección
[Verificación en el host de laboratorio](#verificación-en-el-host-de-laboratorio)) y estas
preguntas abiertas:

| Servicio | Pendiente de decidir |
|---|---|
| backend | si los valores de la petición en mensajes de excepción, los cuerpos de error de Keycloak y la excepción completa del 5xx de pacientes se aceptan, se recortan en el productor (sin perder clase, causa, SQLState ni `correlation_id`) o se mueven a DEBUG |
| keycloak | si `LOGIN_ERROR` en consola (usuario intentado, IP) se acepta o se deja solo en el almacén de eventos (`KC_LOG_LEVEL=…,org.keycloak.events:error`, sin tocar `LoginAuditJob`) |
| postgres | si el `DETAIL` con valores de clave se acepta (diagnóstico más útil) o se reduce (`log_error_verbosity=terse`, que también quita `HINT` y `CONTEXT`) |

Hasta esa decisión la lista por defecto es `[traefik]` y backend, keycloak y postgres siguen en
`default` (sus logs en `json-file` del host, como siempre).

## Etiquetas y campos

| Nombre | Tipo | Valores |
|---|---|---|
| `source` | etiqueta | `docker` (contenedor) o `journald` (aviso de journald) |
| `service` | etiqueta | servicio de Compose (`traefik`, …) o `journald` |
| `stream` | etiqueta | `stdout` (PRIORITY 6) o `stderr` (PRIORITY 3) |
| `level` | etiqueta | `error`, `warn`, `info`, `debug`, `trace`, `fatal`; solo en líneas cuyo formato la expone |
| `container_id` | metadato estructurado | id corto del contenedor (cambia al recrearlo) |
| `correlation_id` | metadato estructurado | UUID de la petición en el backend (`[<uuid>]`, ver [Correlación de peticiones HTTP](../../architecture/overview.md#correlación-de-peticiones-http)) |

La severidad se toma de la palabra de nivel tras la marca de tiempo (Spring Boot y Keycloak:
`INFO`/`WARN`/`ERROR`; Traefik: `INF`/`WRN`/`ERR`; PostgreSQL: `LOG`/`NOTICE` → `info`, `WARNING` →
`warn`, `ERROR`, `FATAL`/`PANIC` → `fatal`, tras `<fecha> <hora> <zona> [pid] [rol@base]`). Una
entrada sin ese formato (salida libre) no tiene `level`; Loki no adivina niveles
(`discover_log_levels: false`).

**Multilínea.** Docker escribe una entrada del journal por línea. Alloy une a la entrada que
las abre las líneas que no empiezan una nueva: las de una traza de Java (`at …`,
`Caused by: …`) y, en PostgreSQL, `DETAIL`, `HINT`, `CONTEXT`, `STATEMENT`, `QUERY` y `LOCATION`
de un error. Así un error llega con su causa y su sentencia en **una** entrada, con la severidad
y el `correlation_id` de la cabecera. Un bloque se cierra con la siguiente línea de apertura, a
los 3 s sin líneas nuevas o a las 256 líneas (el resto de una traza más larga sigue en otro
bloque, sin severidad); Loki trunca además en 256 KB. Los bloques se arman por contenedor y flujo
(stdout/stderr). Si Alloy se detiene con un bloque abierto, ese bloque sigue la regla de
pérdida y duplicación de las posiciones.
Ningún campo con valores por petición es etiqueta: los streams quedan acotados por servicio,
flujo y nivel.

## Archivos

| Archivo | Contenido |
|---|---|
| `infra/observability/compose.yml` | servicio `loki`, acceso de Alloy al journal, volúmenes `loki_data` y `alloy_data` |
| `infra/observability/.env.example` | `OBS_JOURNAL_GID`, `OBS_LOG_PROJECT`, `OBS_LOG_SERVICES` |
| `infra/compose/prod.yml`, `infra/compose/logging.yml` | `extends` de los cuatro servicios y sus dos bases (`journald`, `default`); prefijo, `log_lock_waits` y `PGOPTIONS` de postgres |
| `infra/ansible/inventories/prod/group_vars/all.yml` | `obs_log_services` y `obs_log_privacy_controls_done` (operación) |
| `infra/ansible/roles/observability/tasks/log_policy.yml` | la política, aplicada por converge y por observability |
| `infra/ansible/roles/app/tasks/setup.yml`, `templates/env.j2` | política antes de escribir `.env` y `<SERVICIO>_LOGGING` |
| `infra/ansible/profiles/logs-lab-synthetic.yml` | perfil de laboratorio sintético |
| `infra/observability/alloy/config.alloy` | lectura del journal, selección, procesamiento, envío |
| `infra/observability/loki/config.yaml` | almacenamiento, límites, WAL, retención |
| `infra/observability/prometheus/prometheus.yml` | destinos `alloy` y `loki` con lista de series |
| `infra/observability/prometheus/rules/logs.yml` | reglas de la canalización |
| `infra/observability/grafana/provisioning/datasources/loki.yml` | fuente de datos `loki` |
| `infra/observability/grafana/dashboards/logs.json` | dashboard **Logs** |
| `infra/observability/tests/logs-alerts.test.yml` | pruebas unitarias de las reglas |
| `infra/ansible/roles/observability/` | política de fuentes, journal, drop-in, `.env`, verificación, retirada |
| `infra/ansible/playbooks/observability-logs-rollback.yml` | retirada de Loki y del drop-in |
| `infra/tests/logs-stack-check.sh` | verificación local con journal sintético |
| `infra/tests/log-producers-contract-check.sh` | contrato de los productores: política, `.env`, Compose, Docker |
| `infra/tests/logs-rollback-check.sh` | verificación local de la retirada |
| `infra/tests/logs-e2e-lab.sh`, `infra/tests/logs-lab-ansible-check.sh` | pruebas en un host Linux de laboratorio |

## Recursos y almacenamiento

| Servicio | Límite de memoria | Límite de CPU | Volumen |
|---|---|---|---|
| loki | 768 MiB (`GOMEMLIMIT` 640 MiB) | 0,50 | `loki_data`: chunks, índice, WAL, compactor |
| alloy | 256 MiB (`GOMEMLIMIT` 200 MiB) | 0,25 | `alloy_data`: posiciones de lectura del journal |

- **Loki**: la reproducción del WAL al arrancar se detiene en 384 MB y vuelca a disco antes de
  seguir (`replay_memory_ceiling`), por debajo del límite del contenedor. Escritura limitada a
  4 MB/s (ráfaga 8 MB), 1000 streams y líneas de hasta 256 KB (más largas se truncan). Consultas
  de hasta 14 días, 500 series y 5000 líneas.
- **Journal**: 2 GiB / 7 días para todo el host. Con más volumen, journald borra lo más antiguo
  antes de los 7 días; si Loki estaba caído, lo borrado antes de leerse se pierde.
- Estos valores son de partida, no medidos en el servidor: confirmarlos con
  `infra/tests/logs-e2e-lab.sh` y los paneles de memoria y flujo del dashboard.
- El panel **Memoria residente (RSS) de Loki y Alloy** muestra la RSS del proceso, que incluye
  páginas del binario mapeadas en memoria que el cgroup puede no cobrar al contenedor: no se
  compara con los límites de arriba (en el servidor, Alloy con 221 MiB de RSS tenía 43 MiB
  cobrados). La memoria cobrada al contenedor no se recolecta; en el host: `docker stats` o
  `memory.current`/`memory.events` del cgroup del contenedor.

## Pérdida, duplicación y retención

- **Posiciones.** Alloy guarda el cursor del journal en `alloy_data`; un reinicio o una
  recreación del contenedor continúan donde iban. Las posiciones avanzan al leer, no al
  confirmar Loki: lo leído y aún no enviado al detener Alloy puede perderse, y lo enviado
  después de la última escritura de posiciones puede repetirse al volver. Sin posición (primer
  arranque, volumen borrado), Alloy lee solo las últimas 12 h.
- **Corte de Loki.** Alloy reintenta cada lote con espera creciente (0,5 s a 5 min, 10
  reintentos, ≈8 min). Si Loki vuelve antes, el lote llega. Si no, descarta el lote (`loki_write_dropped_entries_total`,
  **PiedrazulLogsDropped**) y sigue con el siguiente: es pérdida y no se reenvía.
- **Rechazos de Loki.** Límite de tasa (429): se reintenta. Línea demasiado antigua (más de
  7 días) o error definitivo (4xx): se descarta y cuenta como pérdida.
- **Buffer del driver.** En modo `non-blocking`, Docker descarta líneas si el servicio escribe
  más rápido de lo que journald acepta. journald además suprime lo que excede su límite de tasa
  por servicio (`docker.service`, compartido por todos los contenedores) y deja un aviso que sí
  se envía (`service="journald"`).
- **Restablecer posiciones no es un reenvío seguro.** Borrar `alloy_data` hace que Alloy relea
  las últimas 12 h: duplica lo ya enviado (Loki solo descarta duplicados exactos en algunos
  casos), no recupera lo que el journal ya rotó y puede reenviar lo que una fuente escribió antes
  de cambiar la selección.
- **Retención de Loki.** 14 días por antigüedad: el compactor borra lo más viejo cada 10 min
  (con 2 h de demora). **No es un tope del disco**: con más volumen del previsto, `loki_data`
  crece; lo vigila `PiedrazulHostRootFilesystemLow` (raíz del host) y, si el compactor deja de
  correr, `PiedrazulLokiRetentionNotRunning`.

## Instalación con Ansible

`playbooks/observability.yml` instala métricas y logs juntos; en producción lo corre el job
*Observabilidad* del workflow (ver [Instalación y mantenimiento](metricas.md#instalación-y-mantenimiento-github-actions)).
Un cambio de `obs_log_services` u `obs_log_privacy_controls_done` en el inventario es compartido:
el mismo push corre el converge de la aplicación (driver de cada contenedor) y la operación `full`
del stack (selección de Alloy), y el stack queda pendiente mientras su parte no se aplique. El
workflow nunca pasa el perfil de laboratorio (`logs_lab_authorization`).
Requisitos adicionales para los logs:

- systemd-journald con un grupo dueño de los archivos del journal (`systemd-journal` en Ubuntu,
  `obs_journal_group`).
- El GID 473 (dueño del estado de Alloy en su imagen) no debe ser un grupo del host.
- Coordinación con quien administra la configuración del host: el drop-in cambia el journal de
  todo el servidor. Con `obs_journald_manage: false` el rol no lo escribe y exige un journal
  persistente existente.

Pasos que el rol agrega a los de métricas, en este orden:

1. Comprueba la política de fuentes y el GID 473. Si falla, no cambia nada.
2. Escribe el drop-in de journald; si cambió o es más nuevo que el arranque de journald (un run
   anterior lo escribió y se interrumpió), reinicia `systemd-journald` y pasa el journal volátil al
   persistente (`journalctl --flush`). En Ubuntu 24.04 journald conserva los flujos de
   stdout de las unidades al reiniciarse.
3. Descubre el grupo del journal (`/var/log/journal/<machine-id>`) y exige que sus archivos sean
   legibles por ese grupo.
4. Mantiene en `.env` `OBS_JOURNAL_GID`, `OBS_LOG_PROJECT` y `OBS_LOG_SERVICES` (`|` entre
   servicios). Un cambio recrea Alloy.
5. Copia `loki/` con el resto de la configuración; un cambio recrea Loki (digest de su
   configuración).
6. Verifica: Loki `ready`, `loki.source.journal` sano, **Alloy leyó entradas del journal** y la
   fuente de datos Loki sana en Grafana. No exige que una fuente seleccionada haya escrito.

Repetirlo sin cambios no cambia nada. La verificación de los logs no depende de la aplicación: el
propio run escribe en el journal del host.

## Operación y diagnóstico

Dashboard **Logs** (carpeta Piedra Azul), de arriba abajo:

- **Estado de la recolección**: Loki y Alloy (lectura de sus métricas), componentes de Alloy sin
  funcionar y alertas de logs disparadas (0 en verde solo si Prometheus evaluó el grupo
  `piedrazul-logs` en los últimos 2 min; si no, **SIN DATOS**).
- **Volumen y búsqueda de logs**: líneas por servicio (con todos los filtros), errores y avisos
  por servicio (solo con el filtro de servicio) y **Logs filtrados**, la vista general: filtros de
  servicio, severidad (*All* incluye líneas sin severidad), id de correlación (vacío: sin filtrar)
  y búsqueda (expresión regular, sin mayúsculas). Cada línea muestra su servicio y severidad; el
  detalle, todas sus etiquetas y metadatos.
- **Líneas con el id de correlación**: con un id, todas las líneas de cualquier servicio que lo
  contienen (cabecera `X-Correlation-Id`); sin id no muestra nada. En el detalle de una línea del
  backend, el campo `correlation_id` enlaza a la misma búsqueda. Solo el backend escribe el id:
  que otro servicio no aparezca no prueba que no participó.
- **Diagnóstico de la canalización (Alloy → Loki)**: entradas leídas del journal (todas las
  unidades, antes de la selección), seleccionadas, descartadas, última retención, flujo,
  reintentos y rechazos (solo resultados con problema; los envíos correctos están en el flujo) y
  memoria. Los contadores de Alloy muestran **SIN DATOS** (morado) si Alloy no se lee ahora, en
  lugar de una cifra vieja o un 0. La retención sigue el criterio de
  `PiedrazulLokiRetentionNotRunning`: **AÚN NO CORRIÓ** (azul) si Loki arrancó hace menos de 3 h y
  todavía no completó ninguna; **NINGUNA EN 3 H** (naranja) si lleva más de 3 h arriba sin
  completar ninguna.

Cómo distinguir las causas cuando no aparecen logs:

| Señal | Significa | Alerta |
|---|---|---|
| Loki **CAÍDO** | Prometheus no lee las métricas de Loki: normalmente Loki no responde (Alloy retiene y reintenta), aunque puede fallar solo la lectura; si **Logs filtrados** carga líneas recientes, Loki responde | `PiedrazulLokiDown` |
| Alloy **CAÍDO** (y host caído en Plataforma) | Prometheus no lee Alloy: si Alloy no corre, no hay logs ni métricas del host; si siguen llegando líneas nuevas, solo falla la lectura | `PiedrazulTargetDown{job="host"}` |
| Componentes sin funcionar > 0 | normalmente `loki.source.journal` sin directorio del journal que abrir | `PiedrazulAlloyComponentUnhealthy` |
| Componentes sin funcionar **CAÍDO** / **SIN DATOS** | Prometheus no lee Alloy / Alloy responde sin métricas de componentes (o no es destino): el estado de los componentes es desconocido, no sano | `PiedrazulTargetDown{job="host"}` (Alloy caído); SIN DATOS con Alloy arriba no alerta: revisar `metric_relabel_configs` de `alloy` en `prometheus.yml` |
| Entradas leídas = 0 con Alloy arriba | archivos del journal ilegibles para Alloy (grupo) o lectura detenida; el componente sigue sano | `PiedrazulLogsJournalNotRead` (6 h) |
| Leídas > 0, seleccionadas = 0 | ningún servicio seleccionado escribió en el journal: callado, sin driver journald o fuera de la selección | ninguna |
| Reintentos con Loki arriba | Loki rechaza (límite de tasa, error); se ve en **Reintentos, rechazos y descartes** | ninguna mientras no haya pérdida |
| Descartadas > 0 | pérdida consumada | `PiedrazulLogsDropped` |
| Leídas, seleccionadas o descartadas **SIN DATOS** | Alloy no se lee ahora o no publica esa métrica: no se sabe si hubo lectura ni pérdida | `PiedrazulTargetDown{job="host"}` (Alloy caído) |

Un servicio seleccionado sin líneas no es una falla y no alerta. Sin las series de Loki o de
Alloy (no se leen) las reglas de logs no se evalúan: lo dicen `PiedrazulLokiDown` y
`PiedrazulTargetDown{job="host"}`. `PiedrazulLokiRetentionNotRunning` usa una métrica que Loki
publica desde el arranque y actualiza en cada ciclo del compactor aunque no haya datos.

El umbral de 6 h de `PiedrazulLogsJournalNotRead` es provisional: supone que el host escribe en
el journal al menos una vez en ese plazo. Se confirma o ajusta midiendo en el servidor el mayor
intervalo sin entradas (`loki_source_journal_target_lines_total` en el dashboard).

Comandos útiles en el host (`/opt/piedrazul-obs`). Loki y Alloy se piden por nombre absoluto
(`loki.`, `alloy.`): con systemd-resolved sin dominios de búsqueda (Ubuntu 24.04) Docker copia
`search .` al resolv.conf de los contenedores y el `wget` de busybox de la imagen de Prometheus
no resuelve los nombres cortos (`bad address 'loki:3100'`). Prometheus lee esos destinos sin
problema (`up{job=~"alloy|loki"}`); afecta solo a estas sondas manuales.

```bash
# Componentes de Alloy y su salud (loki.source.journal.host)
sudo docker compose exec -T prometheus wget -qO- http://alloy.:12345/api/v0/web/components \
  | jq -r '.[] | "\(.localID) \(.health.state) \(.health.message)"'
# Entradas leídas, seleccionadas, enviadas y descartadas
sudo docker compose exec -T prometheus wget -qO- http://alloy.:12345/metrics \
  | grep -E '^loki_(source_journal_target_lines_total|relabel_entries_written|write_(sent|dropped)_entries_total)'
# Loki listo
sudo docker compose exec -T prometheus wget -qO- http://loki.:3100/ready
# Qué escribe un servicio en el journal y con qué campos
journalctl -o json -n 1 COM_DOCKER_COMPOSE_SERVICE=traefik | jq '{_TRANSPORT, PRIORITY, COM_DOCKER_COMPOSE_PROJECT}'
# Uso y límites efectivos del journal
journalctl --disk-usage; systemd-analyze cat-config systemd/journald.conf | grep -E '^(Storage|System|MaxRetention)'
# Grupo del journal frente al de Alloy
ls -ln /var/log/journal/$(cat /etc/machine-id) | head -3; sudo grep OBS_JOURNAL_GID .env
```

## Problemas frecuentes

| Síntoma | Causa probable | Acción |
|---|---|---|
| Entradas leídas = 0, componente sano | `OBS_JOURNAL_GID` no es el grupo de los archivos | repetir el playbook (lo redescubre) o corregir `.env` y `docker compose up -d alloy` |
| `loki.source.journal` unhealthy: `sd_journal_open_directory failed` | journal no persistente o montaje de `/` ausente | aplicar el drop-in (playbook) y `docker compose restart alloy` |
| Loki reiniciándose en bucle | configuración inválida o memoria insuficiente al reproducir el WAL | `docker compose logs loki`; `docker run --rm -v $PWD/loki:/etc/loki:ro <imagen> -config.file=/etc/loki/config.yaml -verify-config` |
| Reintentos sostenidos (dashboard) | Loki rechaza por límite de tasa (`loki_discarded_samples_total{reason="rate_limited"}`) | buscar el servicio que escribe de más en **Líneas por servicio**; si se agotan, `PiedrazulLogsDropped` |
| Avisos `service="journald"` | journald suprimió líneas de `docker.service` por límite de tasa | hay pérdida en el journal; revisar el servicio ruidoso |
| El playbook falla en la política de fuentes | servicio excluido o condicionado sin controles | corregir `obs_log_services` / `obs_log_privacy_controls_done` |
| El playbook falla por el GID 473 | el host tiene un grupo con ese GID | resolver el conflicto de GID en el host antes de instalar |
| Paneles de logs vacíos con lecturas y selección > 0 | consulta fuera del rango o filtros | ampliar el rango; quitar filtros |

## Retirada

Volver a métricas sin logs son dos pasos, en este orden. El orden evita cualquier estado
degradado intermedio: tras el paso 1 Loki sigue sirviendo, pero ya nadie lo lee.

1. **Desde la versión sin logs**, repetir `playbooks/observability.yml` (mismo inventario que la
   instalación). Prometheus deja de leer `alloy` y `loki` y retira las reglas de logs, Alloy
   vuelve a solo métricas (deja de leer el journal y de usar `alloy_data`) y Grafana retira el
   dashboard de logs. Quedan, sin efecto en las métricas: Loki como contenedor huérfano (sigue
   respondiendo), `/opt/piedrazul-obs/loki`, la fuente de datos Loki en Grafana (provisionada de
   solo lectura: el archivo retirado no la borra), el drop-in y las variables `OBS_*` de logs.
2. **Desde la versión con logs**, `playbooks/observability-logs-rollback.yml`. Comprueba antes
   que el host ya tiene la configuración sin logs (si no, falla sin cambiar nada) y retira, en
   este orden: la fuente de datos Loki (un `deleteDatasources` temporal y un reinicio de
   Grafana), el contenedor de Loki (por etiquetas de Compose), `/opt/piedrazul-obs/loki`, las
   variables de logs de `.env` y el drop-in de journald (reinicia journald). No toca los
   volúmenes de Prometheus y Grafana ni el estado de Grafana (usuarios, preferencias).
   Repetirlo no cambia nada.
3. **Opcional y destructivo**: repetir el paso 2 con `-e obs_logs_purge=true`. Borra `loki_data`
   (todos los logs) y `alloy_data` (posiciones). Repetirlo no cambia nada.

Repetir después el paso 1 tampoco cambia nada: el host queda como antes de instalar los logs,
salvo lo que sigue.

Lo que la retirada no deshace:

- El journal ya escrito en `/var/log/journal` se conserva. Sin el drop-in, journald vuelve a la
  configuración de la distribución; con `Storage=auto` (por defecto) el journal sigue siendo
  persistente mientras exista ese directorio, con los límites por defecto (10 % del sistema de
  archivos, hasta 4 GiB).
- Los servicios de la aplicación siguen en el modo que les da `obs_log_services` (por defecto
  traefik en `journald`). Volver a `default` es un run de converge (y deploy) con la lista sin ese
  servicio: ver [Perfil de laboratorio sintético](#perfil-de-laboratorio-sintético),
  "Reversión".
- Alertmanager y el acceso a Grafana por Access (S3) no son parte de los logs: desde una versión
  con S3, la retirada de logs los deja en marcha. Su retirada está en [Alertas](alertas.md#retirada)
  y [Acceso a Grafana](grafana-acceso.md#activación-y-desactivación).

## Verificación

| Prueba | Dónde | Qué demuestra |
|---|---|---|
| `promtool test rules tests/logs-alerts.test.yml` | cualquier Docker | expresiones y duraciones de las 5 reglas, y que cada causa dispara solo la suya |
| `infra/tests/logs-stack-check.sh` | cualquier Docker (también Docker Desktop) | configuración, Ansible (sintaxis, política, drop-in) y la canalización Alloy → Loki → Grafana real sobre **journals sintéticos**: selección, procedencia (productores que imitan los campos de Docker), etiquetas, correlación, posiciones, cortes de Loki y de Alloy, journal ilegible, PRE/POST, persistencia, aislamiento; inventario aislado del laboratorio sin conectar |
| `ROLLBACK_BASE_REF=<commit sin logs> infra/tests/logs-rollback-check.sh` | cualquier Docker | los dos pasos de la retirada con el playbook real en local: orden obligatorio, sin estado degradado, fuente de datos retirada, estado de Grafana y volúmenes de métricas conservados, repetición y purge (sin el drop-in de journald) |
| `infra/tests/log-producers-contract-check.sh` (`DIND=1` para la rotación efectiva) | cualquier Docker (también Docker Desktop) | `.env` por defecto, de laboratorio y de adopción en operación; `journald` con sus opciones solo donde se pide y `default` idéntico a antes (hash de HEAD, configuración implícita del daemon, rotación 10m × 3 y modo blocking con un daemon `json-file`); valor desconocido rechazado; render determinista; diagnóstico de postgres sin volcados; la política real (casos positivos y negativos); una sola política y una sola lista; llamadas de Compose coherentes; fallo visible de `journald` sin journald |
| `infra/tests/metrics-stack-check.sh` | cualquier Docker | que las métricas siguen igual con Loki y la canalización |
| `LAB_CONFIRM=1 infra/tests/logs-e2e-lab.sh` | host Linux de laboratorio con Docker nativo | campos reales del driver journald (incluidos `_COMM`, `_UID`), falsificación real con `logger --journald`, permisos reales del journal, drop-in efectivo, WAL de Loki ante SIGKILL, avisos de supresión (`BURST=1`), pérdida en un corte largo (`LONG_OUTAGE=1`) |
| `LAB_HOST=<ip> LAB_CONFIRM=<ip> LAB_BASE_REF=<commit sin logs> infra/tests/logs-lab-ansible-check.sh` | controlador contra el laboratorio | instalación, idempotencia, rechazo de fuentes sin cambios, orden y pasos de la retirada, drop-in, reinstalación. Inventario aislado de un host (nunca `inventories/prod`), IP literal, `-F /dev/null` y `StrictHostKeyChecking=yes` |

Que la verificación local pase no demuestra la ingesta desde contenedores reales: el driver
journald y los permisos del journal de un host solo los prueba `logs-e2e-lab.sh`, y los
productores reales de la aplicación solo la verificación del perfil de laboratorio en el host
([lista](#verificación-en-el-host-de-laboratorio)).

Requisitos del laboratorio, además de los de la cabecera de cada script:

- Controlador con `ansible-core` 2.17 o posterior: las colecciones fijadas en
  `infra/ansible/requirements.yml` (`community.docker` 5.2.0, `community.general` 12.5.0) no
  admiten versiones anteriores.
- En el host: el usuario `ansible` con sudo sin contraseña (`obs_owner` y `LAB_USER` por defecto),
  Docker con Compose (rol `docker_host`), `jq` para `logs-e2e-lab.sh` y la red `piedrazul_net`.
- Destinos de métricas de la aplicación. La instalación (y el paso 1 de la retirada con la versión
  sin logs) falla en "Destinos leídos" si `backend`, `keycloak` y `traefik` no están up: no basta
  con crear `piedrazul_net` vacía. Sin la aplicación hacen falta productores **sintéticos** en
  esa red con esos nombres y puertos y en las rutas de `prometheus.yml`
  (`/actuator/prometheus` en 8081, `/metrics` en 9000 y 8082), sin puertos publicados, que
  respondan con `Content-Type: text/plain; version=0.0.4` (Prometheus 3 rechaza la lectura sin
  un tipo de exposición válido). Con ellos el run verifica el stack, no la aplicación: un destino
  sintético up no es evidencia de las métricas reales de backend, Keycloak ni Traefik.
