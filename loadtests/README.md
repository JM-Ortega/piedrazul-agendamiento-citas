# Pruebas de carga: S1 disponibilidad de médicos

Prueba con k6 de `GET /api/doctor/{id}/available-slots?date=YYYY-MM-DD` con un usuario
autenticado. Mide latencia, tasa lograda, errores HTTP y funcionales e iteraciones perdidas
(`dropped_iterations`). No es una prueba de capacidad de producción ni fija un SLA.

| Archivo | Rol |
| --- | --- |
| `s1-available-slots.js` | Script k6: descubrimiento, validación, perfiles y resumen |
| `run.sh` | Corre el script con la imagen fijada `grafana/k6:2.2.0@sha256:9bd01d69…` y guarda resultados |
| `results/` | Evidencia generada (ignorada por Git) |

Requisitos: Docker y Bash (Linux, WSL o macOS). No hace falta instalar k6.

## Qué hace

1. **Setup**: lee `exp` del token y aborta si no cubre la corrida más 60 s. Consulta
   `GET /api/doctor/specialties-with-active-doctors?schedulingOrigin=MANUAL`, toma hasta
   `DATES_PER_DOCTOR` fechas por médico y sondea S1 en cada una; solo usa pares médico/fecha
   con al menos un cupo (hasta `MAX_PAIRS`). Sin pares utilizables aborta: no fabrica datos.
2. **Carga**: recorre los pares en ronda. Cada respuesta cuenta como correcta solo si es 200
   y una lista no vacía de horas `HH:mm[:ss]` (el contrato `List<LocalTime>` del controlador).
3. **Resumen**: `result.json` compacto y un bloque de texto en consola.

Umbrales (todos funcionales; no hay umbrales de latencia porque no hay requisito acordado):
`s1_ok` = 100 %, `http_req_failed{endpoint:s1}` = 0 y, en `load`, `dropped_iterations` = 0.
Si alguno falla k6 sale con 99; si aborta (credenciales, datos, token vencido) sale con 108.

`result.json` resume la corrida en `status`:

| `status` | Significado | Salida de k6 |
| --- | --- | --- |
| `no_measurement` | Abortó en el setup: no hubo solicitudes S1 (`thresholds_passed` es `null`) | 108 |
| `invalid` | Token vencido a mitad de corrida o corrida interrumpida antes de procesar todas las llegadas | 108 u otra ≠ 0 |
| `failed` | Completa, pero fallaron umbrales funcionales o de `dropped_iterations` | 99 |
| `passed` | Completa y aceptada | 0 |

Una solicitud S1 con 401 cuenta como error funcional antes de abortar.

## Perfiles

| Perfil | Ejecutor | Por defecto | Topes |
| --- | --- | --- | --- |
| `smoke` | `per-vu-iterations`, 1 VU | `SMOKE_ITERATIONS=10` | 100 iteraciones, 1 min |
| `load` | `constant-arrival-rate` | `RATE=10` req/s, `DURATION=3m`, `PRE_VUS=2×RATE`, `MAX_VUS=5×RATE` | 50 req/s, 600 s, 100 VUs |

`RATE` es llegadas por segundo, no usuarios simultáneos: k6 usa los VUs que haga falta
(hasta `MAX_VUS`) para sostenerla. Si no alcanza, las llegadas faltantes salen como
`dropped_iterations`, el resumen muestra la diferencia entre lo pedido y lo generado y la
corrida falla. Los 10 req/s × 3 min son una carga inicial de medición, no un requisito de la
clínica.

## Token

El realm no habilita password grant para `piedrazul-frontend` y el login es del tema
Keycloakify, así que el arnés no automatiza el login: recibe un access token de corta vida.
Los tokens duran 300 s, suficiente para `load` por defecto (180 s + 60 s de margen) si se
obtiene justo antes de correr. No hay renovación: si el token vence a mitad de corrida, la
prueba aborta y el resumen la marca **INVÁLIDA**.

Para obtenerlo: inicie sesión en el frontend con un usuario de prueba con rol `SCHEDULER`
(en desarrollo, los agendadores de demostración), abra las herramientas de desarrollador →
Red, copie el valor tras `Bearer ` de cualquier petición a `/api/` y guárdelo en un archivo
fuera del repositorio:

```bash
install -m 600 /dev/null /tmp/pz-token && "${EDITOR:-nano}" /tmp/pz-token
```

`run.sh` acepta `PZ_ACCESS_TOKEN_FILE`, `PZ_ACCESS_TOKEN` o, sin ninguno y con terminal, lo pide
sin eco. El token llega al contenedor por nombre de variable (`-e ACCESS_TOKEN`): no aparece en
la línea de comandos, el script nunca lo imprime y no queda en `results/`. Bórrelo al terminar.

## Ejecución local

Con el stack de desarrollo arriba (`docker compose up -d`) y el backend con
`SPRING_PROFILES_ACTIVE=dev` y `APP_DEMO_SEED_ENABLED=true`:

```bash
PZ_TARGET_URL=http://host.docker.internal:8080 PZ_ACCESS_TOKEN_FILE=/tmp/pz-token PZ_DATASET="demo dev (APP_DEMO_SEED_ENABLED)" loadtests/run.sh smoke
```

```bash
PZ_TARGET_URL=http://host.docker.internal:8080 PZ_ACCESS_TOKEN_FILE=/tmp/pz-token PZ_DATASET="demo dev (APP_DEMO_SEED_ENABLED)" RATE=10 DURATION=3m loadtests/run.sh load
```

`PZ_TARGET_URL` acepta solo un origen `http(s)://host[:puerto]`: se rechaza (sin repetir el
valor) cualquier ruta, query, fragmento o credencial antes de llamar a Docker.

`PZ_DATASET` es una descripción libre de los datos del destino que se copia tal cual a
`result.json`. El arnés no la deduce: sin ella el resultado dice `no declarado`. Declárela
solo con lo que sabe del destino (por ejemplo, que la base local se creó con los seeders de
demostración).

Red de Docker: k6 corre en un contenedor, así que `localhost` es el propio contenedor.

- Backend en el host (`./mvnw spring-boot:run`, `java -jar`): use `host.docker.internal`.
  Docker Desktop (Windows, macOS, WSL) lo define; en Docker Engine de Linux `run.sh` lo agrega
  con `host-gateway`. El backend debe escuchar en todas las interfaces (Spring lo hace por
  defecto). Verificado con Docker Desktop + WSL2 y backend dentro de WSL.
- Backend en contenedor en la red del compose: `PZ_DOCKER_NETWORK=piedrazul_net` y
  `PZ_TARGET_URL=http://backend:8080`.
- No se usa `--network host`: no se comporta igual en todas las instalaciones de Docker Desktop.

## Destino remoto (solo con autorización)

El mismo comando sirve con `PZ_TARGET_URL=https://<dominio>` y un token de ese realm. Antes de
correr `load` contra un entorno compartido hace falta autorización explícita, ventana acordada
y datos sintéticos confirmados en ese destino (los seeders de demostración solo corren con el
perfil `dev`). `PZ_DATASET` debe describir el dataset sintético que el responsable de ese
entorno confirmó, no suponerse por ser un entorno de pruebas. Pase `PZ_TARGET_REVISION` con
la identidad desplegada (p. ej. el digest de la
imagen del backend): `/actuator/info` exige rol y el arnés no puede leerla.

## Resultados

Cada corrida crea `loadtests/results/<UTC>-<perfil>/`:

- `result.json`: `status`, marca de tiempo, revisión de la prueba (`<sha>[-dirty]`) y del
  destino, imagen de k6, dataset (descripción declarada, pares, médicos, origen), carga
  pedida vs. generada
  (`s1_requests`, `s1_rate_per_s`, `dropped_iterations`, `max_vus_active`), latencia de S1 en
  ms (avg, min, med, p90, p95, p99, max), errores (`http_failed_rate`, `functional_errors`,
  `functional_ok_rate`, `null` sin solicitudes S1) y `thresholds_passed`.
- `summary.json`: exportación completa de k6.
- `console.txt`: salida de consola.

La latencia y la tasa de S1 excluyen las peticiones del setup (etiqueta `endpoint:s1`).
`s1_rate_per_s` se calcula sobre la ventana planeada (`DURATION`).

## Limitaciones conocidas

- `invalid` por interrupción se infiere de solicitudes S1 + `dropped_iterations` menores que
  las llegadas planeadas (en `load`, con tolerancia de una llegada en el borde final). Es
  conservador: más de una iteración cortada por el `gracefulStop` también marca la corrida así.
- Datos de demostración: pocos médicos (los de terapia neural, porque sin `patientId` el
  descubrimiento trata al paciente como nuevo) y agendas vigentes hasta 2026-12-31; después
  el setup aborta por falta de pares.
- Tráfico de solo lectura y sin citas reservadas en paralelo: no representa contención con
  escrituras.
- Un único token y usuario: no ejercita la emisión de tokens ni cachés por usuario.
- Un smoke correcto valida el arnés, no el rendimiento.
- Generador y backend en la misma máquina compiten por CPU en corridas locales.
