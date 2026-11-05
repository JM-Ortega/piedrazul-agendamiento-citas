# Logs: journald, Alloy, Loki y Grafana

## Propósito

Instalar, operar, verificar y retirar la recolección privada de logs de Piedra Azul: los
contenedores seleccionados escriben en el journal del host con el driver `journald` de Docker,
Alloy lee ese journal y envía solo las fuentes admitidas a Loki, y Grafana las muestra en el
dashboard **Piedra Azul – Logs**. Forma parte del proyecto Compose `piedrazul-obs` (ver
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

| Fuente | Estado | Condición |
|---|---|---|
| Traefik | admitida | se envía cuando su servicio escribe en el journal |
| Avisos de journald por límite de tasa de `docker.service` | admitidos | se envían siempre; son la evidencia de líneas suprimidas |
| Backend | condicionada | controles de privacidad del productor completos (abajo) |
| Keycloak | condicionada | sin eventos de usuario en stdout, conservando el almacén de eventos y `LoginAuditJob` |
| PostgreSQL, migraciones, el propio stack, otras unidades del host | excluidas | nunca |

Controles de privacidad del backend antes de admitirlo: detalles de error del servidor de pgJDBC,
excepciones con nombres de usuario, números de documento de pacientes en mensajes, valores crudos
en las rutas de excepción de estadísticas y auditoría, cuerpos de error crudos de Keycloak y la
excepción original en la ruta 5xx de pacientes. Hasta que estén completos, sus logs pueden llevar
datos personales y no se envían.

La política se aplica dos veces:

1. **Alloy** (`alloy/config.alloy`, `loki.relabel "select"`) solo deja pasar entradas escritas
   por dockerd del proyecto `OBS_LOG_PROJECT` cuyo servicio coincide con `OBS_LOG_SERVICES`, y
   los avisos de journald de mensajes suprimidos de `docker.service` (ver
   [Procedencia](#procedencia-de-las-entradas)). Todo lo demás se descarta dentro de Alloy.
2. **Ansible** (`roles/observability`, `obs_log_services`) falla sin tocar el host si la lista
   incluye un servicio excluido, uno condicionado que no figura en `obs_log_privacy_controls_done`
   o algo que no es un nombre de servicio.

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

1. En el archivo Compose del servicio (`infra/compose/<servicio>.yml`), un cambio de despliegue
   propio de la aplicación:

   ```yaml
   logging:
     driver: journald
     options:
       mode: non-blocking
       max-buffer-size: 4m
       # Campos COM_DOCKER_COMPOSE_PROJECT y COM_DOCKER_COMPOSE_SERVICE: los usa la selección
       labels: com.docker.compose.project,com.docker.compose.service
       tag: "{{.Name}}"
   ```

   Cambiar el driver recrea el contenedor. Con `journald`, `docker logs` sigue funcionando (lee
   del journal). En modo `non-blocking`, si el buffer de 4 MiB se llena, Docker descarta líneas
   en vez de bloquear al servicio.
2. En el inventario, el servicio en `obs_log_services` (y, si es condicionado, en
   `obs_log_privacy_controls_done`) y repetir `playbooks/observability.yml`.

Hoy ningún archivo Compose de la aplicación activa `journald`: en un host instalado solo llegan a
Loki los avisos de journald, hasta que se active Traefik.

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
`INFO`/`WARN`/`ERROR`; Traefik: `INF`/`WRN`/`ERR`). Una línea sin ese formato (continuación de una
traza, salida libre) no tiene `level`; Loki no adivina niveles (`discover_log_levels: false`).
Ningún campo con valores por petición es etiqueta: los streams quedan acotados por servicio,
flujo y nivel.

## Archivos

| Archivo | Contenido |
|---|---|
| `infra/observability/compose.yml` | servicio `loki`, acceso de Alloy al journal, volúmenes `loki_data` y `alloy_data` |
| `infra/observability/.env.example` | `OBS_JOURNAL_GID`, `OBS_LOG_PROJECT`, `OBS_LOG_SERVICES` |
| `infra/observability/alloy/config.alloy` | lectura del journal, selección, procesamiento, envío |
| `infra/observability/loki/config.yaml` | almacenamiento, límites, WAL, retención |
| `infra/observability/prometheus/prometheus.yml` | destinos `alloy` y `loki` con lista de series |
| `infra/observability/prometheus/rules/logs.yml` | reglas de la canalización |
| `infra/observability/grafana/provisioning/datasources/loki.yml` | fuente de datos `loki` |
| `infra/observability/grafana/dashboards/logs.json` | dashboard **Piedra Azul – Logs** |
| `infra/observability/tests/logs-alerts.test.yml` | pruebas unitarias de las reglas |
| `infra/ansible/roles/observability/` | política de fuentes, journal, drop-in, `.env`, verificación, retirada |
| `infra/ansible/playbooks/observability-logs-rollback.yml` | retirada de Loki y del drop-in |
| `infra/tests/logs-stack-check.sh` | verificación local con journal sintético |
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

`playbooks/observability.yml` instala métricas y logs juntos (ver
[Instalación con Ansible](metricas.md#instalación-con-ansible) para la conexión y el inventario).
Requisitos adicionales para los logs:

- systemd-journald con un grupo dueño de los archivos del journal (`systemd-journal` en Ubuntu,
  `obs_journal_group`).
- El GID 473 (dueño del estado de Alloy en su imagen) no debe ser un grupo del host.
- Coordinación con quien administra la configuración del host: el drop-in cambia el journal de
  todo el servidor. Con `obs_journald_manage: false` el rol no lo escribe y exige un journal
  persistente existente.

Pasos que el rol agrega a los de métricas, en este orden:

1. Comprueba la política de fuentes y el GID 473. Si falla, no cambia nada.
2. Escribe el drop-in de journald; si cambió, reinicia `systemd-journald` y pasa el journal
   volátil al persistente (`journalctl --flush`). En Ubuntu 24.04 journald conserva los flujos de
   stdout de las unidades al reiniciarse.
3. Descubre el grupo del journal (`/var/log/journal/<machine-id>`) y exige que sus archivos sean
   legibles por ese grupo.
4. Mantiene en `.env` `OBS_JOURNAL_GID`, `OBS_LOG_PROJECT` y `OBS_LOG_SERVICES` (`|` entre
   servicios). Un cambio recrea Alloy.
5. Copia `loki/` con el resto de la configuración; un cambio reinicia Loki.
6. Verifica: Loki `ready`, `loki.source.journal` sano, **Alloy leyó entradas del journal** y la
   fuente de datos Loki sana en Grafana. No exige que una fuente seleccionada haya escrito.

Repetirlo sin cambios no cambia nada.

## Operación y diagnóstico

Dashboard **Piedra Azul – Logs** (carpeta Piedra Azul):

- Filtros: servicio, severidad (*All* incluye líneas sin severidad), id de correlación y búsqueda
  (expresión regular, sin mayúsculas).
- **Líneas con el id de correlación**: todas las líneas de cualquier servicio que contienen el id
  (cabecera `X-Correlation-Id`). En el detalle de una línea del backend, el campo
  `correlation_id` enlaza a la misma búsqueda.
- **Salud de la recolección**: Loki y Alloy arriba, componentes de Alloy sin funcionar, entradas
  leídas del journal (todas las unidades, antes de la selección), seleccionadas, descartadas,
  flujo, reintentos y rechazos, memoria y última retención.

Cómo distinguir las causas cuando no aparecen logs:

| Señal | Significa | Alerta |
|---|---|---|
| Loki **CAÍDO** | Loki no responde; Alloy retiene y reintenta | `PiedrazulLokiDown` |
| Alloy **CAÍDO** (y host caído en Plataforma) | Alloy no corre: ni logs ni métricas del host | `PiedrazulTargetDown{job="host"}` |
| Componentes sin funcionar > 0 | normalmente `loki.source.journal` sin directorio del journal que abrir | `PiedrazulAlloyComponentUnhealthy` |
| Entradas leídas = 0 con Alloy arriba | archivos del journal ilegibles para Alloy (grupo) o lectura detenida; el componente sigue sano | `PiedrazulLogsJournalNotRead` (6 h) |
| Leídas > 0, seleccionadas = 0 | ningún servicio seleccionado escribió en el journal: callado, sin driver journald o fuera de la selección | ninguna |
| Reintentos con Loki arriba | Loki rechaza (límite de tasa, error); se ve en **Reintentos, rechazos y descartes** | ninguna mientras no haya pérdida |
| Descartadas > 0 | pérdida consumada | `PiedrazulLogsDropped` |

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
- Si algún servicio de la aplicación se había pasado al driver `journald`, volver a `json-file`
  es un cambio de su archivo Compose.
- Alertmanager y el acceso a Grafana por Access (S3) no son parte de los logs: desde una versión
  con S3, la retirada de logs los deja en marcha. Su retirada está en [Alertas](alertas.md#retirada)
  y [Acceso a Grafana](grafana-acceso.md#desactivación).

## Verificación

| Prueba | Dónde | Qué demuestra |
|---|---|---|
| `promtool test rules tests/logs-alerts.test.yml` | cualquier Docker | expresiones y duraciones de las 5 reglas, y que cada causa dispara solo la suya |
| `infra/tests/logs-stack-check.sh` | cualquier Docker (también Docker Desktop) | configuración, Ansible (sintaxis, política, drop-in) y la canalización Alloy → Loki → Grafana real sobre **journals sintéticos**: selección, procedencia (productores que imitan los campos de Docker), etiquetas, correlación, posiciones, cortes de Loki y de Alloy, journal ilegible, PRE/POST, persistencia, aislamiento; inventario aislado del laboratorio sin conectar |
| `ROLLBACK_BASE_REF=<commit sin logs> infra/tests/logs-rollback-check.sh` | cualquier Docker | los dos pasos de la retirada con el playbook real en local: orden obligatorio, sin estado degradado, fuente de datos retirada, estado de Grafana y volúmenes de métricas conservados, repetición y purge (sin el drop-in de journald) |
| `infra/tests/metrics-stack-check.sh` | cualquier Docker | que las métricas siguen igual con Loki y la canalización |
| `LAB_CONFIRM=1 infra/tests/logs-e2e-lab.sh` | host Linux de laboratorio con Docker nativo | campos reales del driver journald (incluidos `_COMM`, `_UID`), falsificación real con `logger --journald`, permisos reales del journal, drop-in efectivo, WAL de Loki ante SIGKILL, avisos de supresión (`BURST=1`), pérdida en un corte largo (`LONG_OUTAGE=1`) |
| `LAB_HOST=<ip> LAB_CONFIRM=<ip> LAB_BASE_REF=<commit sin logs> infra/tests/logs-lab-ansible-check.sh` | controlador contra el laboratorio | instalación, idempotencia, rechazo de fuentes sin cambios, orden y pasos de la retirada, drop-in, reinstalación. Inventario aislado de un host (nunca `inventories/prod`), IP literal, `-F /dev/null` y `StrictHostKeyChecking=yes` |

Que la verificación local pase no demuestra la ingesta desde contenedores reales: el driver
journald y los permisos del journal de un host solo los prueba `logs-e2e-lab.sh`.

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
