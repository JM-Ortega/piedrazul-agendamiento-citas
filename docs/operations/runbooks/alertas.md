# Alertas: entrega a Discord

## Propósito

Llevar a Discord, sin cambiar su semántica, las alertas que Prometheus ya evalúa
(`prometheus/rules/alerts.yml` y `logs.yml`), con disparo, recordatorio y resolución, y hacer
visible al mantenedor cuándo la entrega falla.

**Estado.** El repositorio implementa y prueba en local la entrega con un receptor sintético con la
interfaz de Discord. Fuera del repositorio, el operador **preparó** (informado el 2026-11-04): el
servidor de Discord `Piedra Azul`, el canal `#alertas` y el webhook `Piedra Azul Monitoring`, cuya
URL guarda en 1Password (nunca en el repositorio ni en el inventario). **No integrado**: el webhook
no está instalado en ningún host, el receptor sigue en `none` (Alertmanager recibe y no envía) y
ninguna notificación real se envió. Es un registro de lo preparado, no una garantía del
repositorio: confirmarlo en Discord antes de activar.

## Decisión

| Opción | Semántica de las 12 reglas | Operación | Elegida |
|---|---|---|---|
| **Alertmanager** (un contenedor, receptor `discord_configs` nativo) | intacta: Prometheus sigue evaluando las mismas reglas y sus pruebas `promtool` no cambian | un servicio más (≈ 64 MiB, sin puerto ni estado crítico); agrupación, deduplicación, reintentos, resolución y métricas de entrega incluidos | sí |
| Alertas de Grafana (reglas gestionadas por Grafana) | habría que reescribir las 12 reglas como consultas de Grafana: otra evaluación, sin `promtool test rules` | sin servicio nuevo, pero Grafana pasa a ser crítico para alertar y su configuración vive en su base | no: cambia la arquitectura de evaluación aprobada |
| Grafana como Alertmanager externo de Prometheus | intacta | depende de una API de Grafana no pensada para ese uso, con credenciales para Prometheus | no |
| Webhook propio | intacta | un servicio a mantener sin agrupación ni reintentos probados | no |

Alertmanager es la opción mínima que conserva la semántica y la evaluación de Prometheus.

## Arquitectura

```
Prometheus ──(obs_net, cada evaluación)──▶ Alertmanager :9093 ──HTTPS──▶ Discord (webhook)
  rules/*.yml                               discord.yml | none.yml
  ALERTS en Grafana                         webhook: /run/secrets/discord_webhook_url
```

- `compose.yml`: servicio `alertmanager` (imagen fijada por digest, UID 65534, raíz de solo
  lectura, `cap_drop: ALL`, sin clúster, sin puerto publicado, solo `obs_net`, volumen
  `alertmanager_data` con silencios y el registro de notificaciones enviadas).
- `OBS_ALERT_RECEIVER` elige el archivo: `none.yml` (por defecto) o `discord.yml`.
- `prometheus.yml`: `alerting` hacia `alertmanager:9093` y el destino `alertmanager` (métricas de
  entrega).
- `rules/notifications.yml`: tres reglas sobre la propia entrega; las de S1/S2 no cambian. No hay
  latido ni otra alerta siempre en firing: Discord solo recibe incidentes y sus resoluciones.

## Semántica de la entrega (`discord.yml`)

| Parámetro | Valor | Efecto |
|---|---|---|
| `group_by` | `alertname` | un mensaje por alerta, con todas sus instancias (p. ej. los destinos caídos juntos) |
| `group_wait` | 30 s | primer aviso de un grupo nuevo |
| `group_interval` | 5 min | cambios de un grupo ya avisado, incluida la **resolución**: llega hasta 5 min después de que la condición se apaga |
| `repeat_interval` | 12 h | recordatorio de lo que sigue en firing |
| `send_resolved` | true | mensaje `[RESUELTA]` |
| inhibiciones, silencios | ninguno por configuración | nada se oculta sin una acción explícita del operador |

Mensaje: título `[<entorno>] [FIRING|INFO|RESUELTA] <alerta> (<severidad>)` y, por alerta, `summary`,
`description` y la hora. Solo usa anotaciones y etiquetas acotadas de las reglas: sin datos de
pacientes, usuarios, URL ni IP. La severidad distingue incidentes de avisos informativos:

| Severidad | En firing | Resuelta |
|---|---|---|
| `critical` (o sin severidad) | `[FIRING]`, 🔴 | `[RESUELTA]`, ✅ |
| `warning` | `[FIRING]`, 🟠 | `[RESUELTA]`, ✅ |
| `info` (ninguna regla la usa; p. ej. la prueba manual de abajo) | `[INFO]`, 🔵: no pide acción | `[RESUELTA]`, ⚪ "ya no está activa" y su descripción |

El color del embed lo pone Alertmanager (rojo en firing, verde resuelta) y Discord no permite
cambiarlo desde la plantilla: un `[INFO]` llega con el borde rojo; la severidad se lee en el título
y el ícono.

**Entorno de origen.** El mismo webhook se reutiliza al principio para el staging temporal y para
el despliegue definitivo, así que cada mensaje empieza por el entorno (`[staging]`,
`[produccion]`). Viene de `OBS_ENVIRONMENT` (estado deseado `obs_environment`, obligatorio con el
receptor `discord`): Prometheus lo pone como etiqueta externa `environment` en las alertas que envía
a Alertmanager (`prometheus.yml`, `external_labels`). No entra en las series, en `ALERTS` ni en las
reglas, que no cambian (probado), y no requiere ningún componente nuevo. Sin `OBS_ENVIRONMENT` las alertas de
Prometheus dicen `[sin-definir]` (el rol no lo permite con `discord`); una alerta sin la etiqueta,
como una inyectada a mano en Alertmanager, dice `[sin-entorno]`.

## Reglas de la entrega

| Alerta | Condición | Visible en |
|---|---|---|
| `PiedrazulAlertmanagerDown` (critical) | `up{job="alertmanager"} == 0` 5 min | Grafana / Prometheus (Discord no puede recibirla) |
| `PiedrazulPrometheusNotificationsFailing` (warning) | errores o descartes de Prometheus al enviar a Alertmanager en 15 min | Grafana; Discord si Alertmanager entrega |
| `PiedrazulAlertNotificationsFailing` (critical) | `alertmanager_notifications_failed_total` crece en 15 min (envíos perdidos tras los reintentos) | Grafana; Discord cuando el webhook vuelve |

**Lo que no se notifica.** No hay un mensaje periódico de "todo bien": el silencio en Discord no
prueba que la cadena funcione. Lo que nadie dentro del servidor puede avisar no llega a ningún lado:

- host o Prometheus caídos: nada se evalúa;
- Alertmanager caído: `PiedrazulAlertmanagerDown` solo se ve en Grafana;
- webhook revocado o borrado sin nada que enviar: se descubre en el siguiente incidente, cuando su
  envío falla (`PiedrazulAlertNotificationsFailing` en Grafana).

Para eso hay que mirar Grafana (Plataforma: **Alertas que requieren atención** en 0 y verde
significa reglas evaluadas hace menos de 2 min y ningún incidente; **SIN DATOS** (morado), que
Prometheus no evalúa) y probar la entrega de punta a punta al instalar, rotar el webhook y en cada verificación
del entorno (paso 3 de [Credencial](#credencial)). La disponibilidad pública de la aplicación la
vigila además UptimeRobot cuando se active ([Monitoreo externo](monitoreo-externo.md)).

## Credencial

El webhook de Discord es la única credencial de la entrega. Consumidores y lugares:

| Dónde | Forma |
|---|---|
| 1Password | origen (item del webhook `Piedra Azul Monitoring`), custodiado por el operador |
| GitHub | secreto `OBS_DISCORD_WEBHOOK_URL` del environment `production-hetzner`, entregado solo al paso que aplica el stack (operación `full`); obligatorio con el receptor `discord`: la cadena nunca usa en su lugar el que conserva el host |
| Controlador de Ansible | variable de entorno `OBS_DISCORD_WEBHOOK_URL` (`no_log`, nunca en `-e`, inventario ni repositorio) |
| Host | `/opt/piedrazul-obs/secrets/discord_webhook_url`: directorio root 0700, archivo del UID 65534 0400 (ningún proceso sin privilegios del host lo lee; Alloy, que monta la raíz como `nobody`, tampoco atraviesa el directorio) |
| Contenedor | secreto de Compose montado solo en `alertmanager` (`webhook_url_file`). Es un montaje de archivo: reemplazarlo en el host no llega al contenedor en marcha hasta recrearlo: el rol lo recrea cuando el inodo que ve el contenedor no es el del archivo del host, también en un reintento tras una interrupción |

No está en `.env`, en la configuración de Alertmanager (su estado muestra solo la ruta del
archivo), en las variables de ningún contenedor ni en los logs (Alertmanager oculta la URL en sus
errores; probado con la red caída).

## Activación

1. **Discord: verificar lo preparado, no recrearlo.** En el servidor `Piedra Azul`, canal
   `#alertas` → Integraciones → Webhooks, que exista `Piedra Azul Monitoring` y que su URL sea la
   guardada en 1Password. Si no coincide, decidir antes de seguir; no crear otro webhook salvo
   para rotar.
2. Instalar (una vez por host; queda guardado):

   ```bash
   cd infra/ansible
   read -rs OBS_DISCORD_WEBHOOK_URL && export OBS_DISCORD_WEBHOOK_URL   # pegar desde 1Password
   ansible-playbook playbooks/observability.yml -e obs_alert_receiver=discord -e obs_environment=staging
   unset OBS_DISCORD_WEBHOOK_URL
   ```

   (`op read` de la CLI de 1Password sirve igual para poblar la variable sin pegarla.) En el
   despliegue definitivo, `-e obs_environment=produccion`. El rol valida la forma
   (`https://discord.com/api/webhooks/<id>/<token>`) sin mostrarla, escribe el archivo, recrea
   Alertmanager con `discord.yml` y verifica que corre con ese receptor y que Prometheus lleva la
   etiqueta de ese entorno. Después, `observability.yml` sin `-e` ni la variable conserva el
   receptor, el entorno y el webhook ([estado deseado](grafana-acceso.md#estado-deseado)).
3. **Verificación en vivo** (pendiente; también tras rotar el webhook y en cada verificación del
   entorno): prueba manual de extremo a extremo, disparo y resolución, sin tocar ninguna regla:

   ```bash
   cd /opt/piedrazul-obs
   ENV_NAME="$(sudo sed -n 's/^OBS_ENVIRONMENT=//p' .env)"
   sudo docker compose exec alertmanager amtool alert add PiedrazulPruebaManual severity=info \
     "environment=${ENV_NAME}" \
     --annotation='summary="Prueba manual de entrega"' --annotation='description="Prueba"' \
     --end="$(date -u -d '+3 min' +%Y-%m-%dT%H:%M:%SZ)" --alertmanager.url=http://localhost:9093
   ```

   Esta alerta entra directo en Alertmanager, sin pasar por Prometheus: **no hereda** la etiqueta
   externa `environment`, por eso el comando la da explícita (la del `.env` del host). Sin ella el
   título dice `[sin-entorno]`. Debe llegar `[<entorno>] [INFO] PiedrazulPruebaManual (info)` en
   ≈ 30 s y `[RESUELTA]` a los pocos minutos de vencer, en `#alertas`. Las alertas de las reglas sí
   llevan el entorno siempre (las etiqueta Prometheus). Verificado en local con la imagen fijada de
   Alertmanager, `discord.yml` y un receptor sintético: con la etiqueta, `[staging]`; sin ella,
   `[sin-entorno]`.

En producción, el receptor (`discord`) y el entorno (`produccion`) están en el inventario y el
webhook en el secreto del environment: Run I y cada operación `full` los aplican. Lo que la cadena
verifica es configuración y estado local (receptor cargado, archivo presente con su dueño y modo,
Alertmanager viendo el archivo vigente, Prometheus entregando a Alertmanager); **nunca la entrega
a Discord**, que solo prueba el paso 3.

## Rotación y desactivación

- **Rotar (producción)**: actualizar el secreto `OBS_DISCORD_WEBHOOK_URL` y correr el workflow con
  `observability=full` (sin otros flags); después, el paso 3 y borrar el webhook anterior en Discord.
- **Rotar (manual)**: crear el webhook nuevo, repetir el paso 2 con él y borrar el anterior en Discord. El
  rol reemplaza el archivo y recrea Alertmanager (unos segundos; las alertas que llegan mientras
  tanto las reintenta Prometheus). Su registro de notificaciones está en `alertmanager_data`:
  recrearlo no reenvía lo ya entregado (probado). Sin recrearlo, el contenedor sigue usando el
  webhook anterior (probado: el montaje de archivo no ve el reemplazo atómico; con Docker Desktop
  un `restart` además falla al montar). Manualmente: `docker compose up -d --force-recreate
  alertmanager`.
- **Desactivar**: repetir el playbook con `-e obs_alert_receiver=none` (los runs siguientes sin
  `-e` lo mantienen). El archivo y el entorno quedan; volver a activar es
  `-e obs_alert_receiver=discord`. Para retirar el webhook, vaciar el archivo y revocarlo en Discord
  (afecta a todos los entornos que lo comparten).
- **Separar entornos más adelante**: crear un webhook propio para producción y rotar solo en ese
  host; el entorno en el título sigue igual.

## Fallas de entrega

| Falla | Comportamiento (probado en local salvo donde se indica) | Señal |
|---|---|---|
| Discord 5xx transitorio | reintento con espera creciente dentro del ciclo del grupo; si una entrega posterior llega, no se pierde nada | `alertmanager_notification_requests_failed_total` |
| Discord 429 (límite de tasa) | **no probado**; con alertas agrupadas y sin mensajes periódicos, el volumen esperado está muy por debajo del límite de un webhook | igual que 5xx si se agota |
| Webhook borrado o revocado (404) | no se reintenta; el envío se da por perdido | `PiedrazulAlertNotificationsFailing` en Grafana; llega a Discord cuando el webhook vuelve a funcionar |
| Red o DNS caídos | error registrado sin la URL; reintento | logs de Alertmanager; si se agota, la regla anterior |
| Alertmanager caído | Prometheus acumula y reintenta; nada se notifica | `PiedrazulAlertmanagerDown` en Grafana (no llega a Discord) |
| Prometheus o el host caídos | nada se evalúa ni se notifica | **nada lo avisa**: Grafana sin datos (o inaccesible); UptimeRobot si la aplicación también cae |
| Prometheus reiniciado durante una alerta | comportamiento propio de Prometheus 3.15, anterior a S3: una alerta con `for` ≤ 10 min no recupera su estado (`--rules.alert.for-grace-period`) y vuelve a *pending*; la serie `ALERTS` anterior queda visible ≈ 5 min. Observado (experimento acotado): sin mensajes nuevos al reiniciar y una sola `[RESUELTA]` correcta al recuperarse. **No probado**: una caída que siga más de ≈ 5 min tras el reinicio podría producir una `[RESUELTA]` prematura y un `[FIRING]` nuevo al cumplirse otra vez el `for` | Discord y `/api/v1/alerts` (no la serie `ALERTS`, que puede ir atrasada) |

## Diagnóstico

```bash
cd /opt/piedrazul-obs
sudo docker compose logs --tail 100 alertmanager
sudo docker compose exec prometheus wget -qO- http://alertmanager.:9093/api/v2/alerts | jq '.[].labels.alertname'
sudo docker compose exec prometheus wget -qO- http://localhost:9090/api/v1/alertmanagers
sudo docker compose exec prometheus wget -qO- http://alertmanager.:9093/metrics | grep -E 'alertmanager_notifications_(total|failed_total)'
```

| Síntoma | Causa probable | Acción |
|---|---|---|
| no llega nada, ni la prueba manual | receptor `none`, webhook vacío o revocado, Alertmanager caído | `.env` (`OBS_ALERT_RECEIVER`), logs; `PiedrazulAlertNotificationsFailing` en Grafana |
| `failed_total{reason="clientError"}` crece | webhook borrado (404) o URL mal copiada | rotar (paso 2) |
| llega el disparo pero tarda la resolución | `group_interval` de 5 min | esperado |
| `up` falla con `secret ... discord_webhook_url ... no such file` | falta el archivo (instalación manual) | repetir el playbook o crearlo vacío |

## Retirada

Volver a la versión anterior a S3 deja Alertmanager como contenedor huérfano (el Compose anterior
no lo declara). Tras repetir `observability.yml` de esa versión:

```bash
cd /opt/piedrazul-obs
sudo docker compose -p piedrazul-obs rm -sf alertmanager
sudo docker volume rm piedrazul-obs_alertmanager_data
sudo rm -rf /opt/piedrazul-obs/alertmanager /opt/piedrazul-obs/secrets
```

y revocar el webhook en Discord. Las reglas y Prometheus quedan como en S2.

## Pruebas

- `infra/observability/tests/notifications-alerts.test.yml` (`promtool test rules`): normal, falla
  y sin datos de cada regla de la entrega.
- `infra/tests/alert-delivery-check.sh` (Docker, ≈ 15 min, sin red externa; `STATIC_ONLY=1` solo
  configuración y plantilla): la plantilla por severidad y estado (`amtool template render` con
  grupos sintéticos), receptor `none`,
  activación (una sonda info llega como `[INFO]`, nada periódico), reintentos (500, 500, 204),
  resolución (✅ warning, ⚪ info), deduplicación, una regla real
  (`PiedrazulTargetDown`), webhook revocado (404) con su alerta, rotación recreando el contenedor (sin
  recrearlo no se aplica), red caída y ausencia del token en logs, estado y configuración.
