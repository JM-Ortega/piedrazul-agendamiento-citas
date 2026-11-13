# Reconciliar la configuración de logs de postgres y keycloak

Corrección **única y autorizada** de contenedores que se crearon antes de que el daemon de Docker
corriera con `/etc/docker/daemon.json` y que, por eso, no tienen la rotación de logs
(`json-file`, `max-size` / `max-file`). No es parte de ningún workflow, converge ni deploy: la
ejecuta una persona autorizada, en el host.

## Por qué existe

Un contenedor toma su configuración de logs **al crearse**: el `HostConfig.LogConfig` que le dio el
daemon en ese momento. Corregir el daemon después no cambia los contenedores que ya existen, y
Compose no los recrea porque su configuración no cambió (la base `default` de
[`logging.yml`](../../../infra/compose/logging.yml) no declara nada propio: hereda del daemon). Un
`json-file` sin `max-size` crece sin límite.

Dos cosas lo evitan en adelante:

- **Orden.** El role `docker_host` reinicia el daemon al terminar de configurarlo, antes de que
  exista cualquier contenedor, y espera por estado a que responda con esa configuración
  ([ANSIBLE.md](../../../infra/ansible/ANSIBLE.md#docker_host)).
- **Verificación.** La identidad en ejecución (`verify_running_images.yml`) comprueba, solo en
  producción (`app_verify_log_config: true`), que cada contenedor tenga la configuración que dicta
  la política: `journald` para los servicios de `obs_log_services` y, para el resto, el driver y
  cada opción de `log-opts` de `daemon.json`. `daemon.json` es la única fuente de los valores; no se
  repiten en Compose. Un contenedor que no la cumple hace fallar el converge o el deploy con
  `LOGS: <servicio> usa …` y remite a este documento. Ningún run lo recrea para corregirlo.

Este procedimiento es el que corrige el contenedor que la verificación señala.

## Qué hace y qué no hace

[`infra/ops/reconcile-container-logging.sh`](../../../infra/ops/reconcile-container-logging.sh) recrea
**solo** postgres y keycloak, con el mismo contrato de Compose que producción (proyecto `piedrazul`,
`/opt/piedrazul/compose/prod.yml`, `/opt/piedrazul/.env` e `image.env`), y **como root**: el `.env` es
`root:root 0600`.

- El volumen de datos de postgres (`piedrazul_pgdata`) se conserva; Keycloak no tiene datos propios
  (su realm, usuarios, clientes, roles y secretos viven en PostgreSQL).
- Nunca borra volúmenes, restaura, ejecuta `down`, ni limpia lo que no entienda. Ante un estado
  ambiguo se detiene con código `3` y se escala.
- No imprime variables de entorno, contraseñas ni el contenido del respaldo, y nunca ejecuta
  `docker compose config` (que volcaría los secretos interpolados).
- No toca Traefik ni el resto de servicios.

## Autorización

`check` solo lee. `apply` exige las dos cosas:

- `--confirm <id>`: el id que calculó `check` sobre el estado observado (contenedores, volumen,
  `daemon.json`). Si el estado cambió entre `check` y `apply`, el id ya no corresponde y no se actúa.
- `--ack-no-active-run`: el reconocimiento de que ningún run de producción está en curso, en cola o
  esperando aprobación. Se comprueba **antes**, desde un puesto de trabajo:

  ```bash
  gh run list --workflow production-hetzner.yml --limit 5 --json databaseId,status,displayTitle \
    --jq '.[] | select(.status != "completed")'
  ```

  Debe no devolver nada. Hasta terminar, no se lanza ningún run.

### Qué excluye el lock y qué no

`apply` toma `flock` sobre `/opt/piedrazul/db.lock`, el mismo archivo que usan la reconciliación de la
base (`db_converge.yml`) y la migración (`migrate.yml`). Excluye **una migración o una reconciliación
de la base concurrentes**. **No** excluye a un converge o un deploy en curso (sus recreaciones de
Compose no toman ese lock), ni a otra persona con acceso a Docker, ni lo que haga el grupo de
concurrencia de GitHub (que solo ordena runs de ese workflow). Esa exclusión es del operador y por
eso `apply` pide el reconocimiento explícito. Además se detiene si ve otro proceso `docker compose`.

`apply` toma el lock **antes** de observar el estado y de comparar el id: una segunda ejecución que
esperó el lock ve lo que dejó la primera y termina con «nada que hacer» o con un id que ya no
corresponde; no repite ninguna recreación. Si no obtiene el lock en 60 s, se detiene sin cambios.

## Procedimiento

> Todo en el host, como root. El script se entrega **completo** por stdin y se ejecuta con `bash -s`
> (ver [Entrega del script](#entrega-del-script-y-conexión-caída)). Las dos formas de abajo hacen lo
> mismo; cambia solo el intérprete desde el que se lanza `ssh.exe`.
>
> - **bash / zsh (también dentro de WSL)**, desde la raíz del repositorio:
>
>   ```bash
>   ssh.exe piedrazul-server-ops 'sudo bash -s -- check' < infra/ops/reconcile-container-logging.sh
>   ```
>
> - **PowerShell (Windows PowerShell 5.1 o PowerShell 7)**: `<` no existe en PowerShell, y canalizar el
>   archivo con `Get-Content -Raw | ssh.exe …` **no es equivalente** (PowerShell 5.1 recodifica la
>   salida a ASCII —los acentos se corrompen— y añade un salto de línea CRLF; comprobado: el hash del
>   texto que recibe el otro extremo no coincide con el del archivo). La redirección de `cmd.exe` sí
>   entrega los bytes tal cual:
>
>   ```powershell
>   $script = (Resolve-Path infra\ops\reconcile-container-logging.sh).Path
>   cmd /c "ssh.exe piedrazul-server-ops ""sudo bash -s -- check"" < ""$script"""
>   ```
>
>   Con una ruta UNC de WSL (`\\wsl.localhost\…`) `cmd` avisa «UNC paths are not supported» y usa el
>   directorio de Windows como actual; es inofensivo porque la ruta del script es absoluta.
>
> El archivo debe conservar finales de línea **LF**. Una copia con CRLF no se ejecuta a medias: bash la
> rechaza con un error de sintaxis antes de actuar (probado). Para pasar el id y el reconocimiento de
> `apply`, se sustituye `-- check` por `-- apply --confirm <id> --ack-no-active-run` en cualquiera de las
> dos formas.

**1. Estado y plan (solo lectura)**

```bash
ssh.exe piedrazul-server-ops 'sudo bash -s -- check' < infra/ops/reconcile-container-logging.sh
```

```powershell
cmd /c "ssh.exe piedrazul-server-ops ""sudo bash -s -- check"" < ""$script"""
```

Resultados posibles (código de salida):

| Salida | Código | Significado |
|---|---|---|
| `RESULTADO: nada que hacer` | 0 | los logs de postgres y keycloak ya son conformes. No hay nada que ejecutar; no se repite. La salud que muestra el estado es informativa: este procedimiento no la repara. |
| `RESULTADO: plan <id> (initial)` | 0 | ambos corren y sanos, postgres sin corregir. Primera ejecución. |
| `RESULTADO: plan <id> (recovery)` | 0 | continuación de un intento anterior (Keycloak detenido, postgres ya recreado, etc.). |
| `ESCALAR …` | 3 | estado ambiguo o inesperado: ver [paradas](#paradas-escalar). No se hace nada. |
| `RECHAZADO …` | 2 | precondición no cumplida (permisos, argumentos, id). |

El plan describe exactamente qué hará (`respaldo lógico + recrear postgres → recrear keycloak`, solo
`recrear keycloak`, o `arrancar keycloak sin recrearlo`). Revisarlo es la autorización.

**2. Aplicar** (solo con autorización explícita para esta operación)

```bash
ssh.exe piedrazul-server-ops 'sudo bash -s -- apply --confirm <id> --ack-no-active-run' < infra/ops/reconcile-container-logging.sh
```

```powershell
cmd /c "ssh.exe piedrazul-server-ops ""sudo bash -s -- apply --confirm <id> --ack-no-active-run"" < ""$script"""
```

Antes de cualquier cambio (en `check` y de nuevo en `apply`, ya con el lock) se comprueba, con un
`up --dry-run` de solo lectura, que Compose **no tiene otro cambio de configuración pendiente** en
postgres ni en keycloak: `--force-recreate` aplicaría toda la configuración pendiente del servicio, no
solo los logs, y quien autoriza el plan solo está autorizando los logs. Un Keycloak detenido puede
legítimamente necesitar arrancar; que Compose lo recree por otra causa no se acepta. Si hay un cambio
pendiente, o el dry-run falla o no informa, se detiene con código `3` sin cambiar nada: aplicar o
revertir ese cambio con el converge normal y volver a ejecutar `check`.

Pasos (cada uno se verifica antes del siguiente):

1. **Sonda.** Se crea —sin arrancar— y se borra un contenedor con la imagen de postgres, y se
   comprueba que hereda la rotación de `daemon.json`. Si no la hereda (el daemon no corre con esa
   configuración), se detiene con código `3` **antes de cambiar nada**: recrear no serviría.
2. **Detiene Keycloak** (con la base arriba): sus transacciones y su membresía de clúster terminan
   limpias y no queda conectado a una base que se reinicia.
3. **Respaldo lógico** (`pg_dump --format=custom`) en `/root/piedrazul-reconcile/`, directorio `0700`,
   archivo `0600`. Se comprueba antes que hay espacio y que se puede escribir; si el respaldo falla,
   no se recrea nada y Keycloak —que se detuvo para esto— se vuelve a arrancar.
4. **Recrea postgres** (`--force-recreate --no-deps --pull never --wait`). Antes de recrear guarda la
   identidad del volumen (nombre, fecha de creación y punto de montaje); después verifica contenedor
   nuevo, que monta **ese mismo volumen** (cualquier otro se rechaza, sin borrar ni reasignar nada),
   misma imagen que `image.env`, healthy, logs conformes y los mismos conteos de Keycloak en la base
   (`realm`, `user_entity`, `client`).
5. **Keycloak.** Si no es conforme, lo recrea; si solo estaba detenido y es conforme, arranca el mismo
   contenedor sin recrearlo. Verifica imagen, healthy y logs.
6. **Estabilidad.** Un `up --dry-run` de Compose no debe tener nada que recrear: un converge posterior
   no recreará lo reconciliado. Falla cerrado: si el propio comando de Compose termina con error, o su
   salida no informa a postgres y keycloak como `Running` sin cambios, o menciona alguna acción, el
   resultado **no** es éxito (la reconciliación ya se aplicó en ese punto: volver a ejecutar `check`).

**3. Después**

- Lanzar Run I (u otro run de producción): su converge **clasifica Keycloak sobre la base** (debe
  informar P1) y la verificación de logs lo confirma. Esa clasificación es la prueba de que el realm,
  los usuarios, los clientes, los roles y los secretos siguen ahí; el script solo prueba conteos.
- Borrar el respaldo cuando Run I haya verificado (contiene credenciales y datos): ver
  [Respaldo](#respaldo).

## Entrega del script y conexión caída

El script se entrega por stdin (`bash -s`), que bash lee a medida que ejecuta. Por eso todo su cuerpo
está dentro de una función que se invoca en la última línea: un script truncado, una transferencia
cortada o una copia con CRLF es un error de sintaxis **antes** de ejecutar nada, nunca un prefijo
ejecutado.

Una vez dentro de `apply`, el script ignora `HUP` y `PIPE` para que una conexión SSH caída no lo mate en
su siguiente salida. Eso es **mejor esfuerzo, no una garantía de que la operación termine**: no impide
que el proceso muera por otras causas (`SIGKILL`, un `sudo` o `sshd` terminados, el reinicio del host,
una desconexión que arrastre al grupo de procesos), y la salida posterior a la caída se pierde. Tras
**cualquier** desconexión o interrupción el estado es desconocido hasta que se ejecute un `check`
**nuevo, de solo lectura**, sobre una conexión nueva; hasta entonces no se lanza otro `apply`, ni
ninguna otra acción. `check` dice si todo quedó conforme, si hay una continuación (`recovery`) o si hay
que escalar. Con `INT`/`TERM` el script avisa de que el estado puede ser parcial y no deshace nada.

## Repetición y continuación

No hay marcador de "ya hecho": el criterio es **el estado**. Un contenedor cuya configuración ya es
conforme no se toca, por lo que repetir `check` o `apply` es seguro. `--force-recreate` solo se usa
sobre un contenedor no conforme.

Si `apply` se interrumpe o falla, el estado puede ser parcial. No se deshace nada (salvo volver a
arrancar Keycloak si el respaldo falló antes de tocar la base). Se vuelve a ejecutar `check`:

| Situación tras el fallo | Qué ofrece `check` | Qué hacer |
|---|---|---|
| Keycloak detenido, postgres sin corregir (cortó antes o durante el respaldo) | `recovery`: respaldo nuevo + recrear postgres + Keycloak | `apply` con el id nuevo |
| La recreación de postgres falló y la base no se tocó | igual que arriba | diagnosticar (`docker logs postgres`) y `apply` |
| postgres ya recreado, Keycloak detenido | `recovery`: solo Keycloak (sin respaldo ni segunda recreación de postgres) | `apply` |
| Todo conforme, Keycloak solo detenido | `recovery`: arrancarlo sin recrearlo | `apply` |
| Keycloak recreado pero no pasa su healthcheck | `nada que hacer` (sus logs ya son conformes) con `salud=unhealthy` a la vista | este procedimiento no repara ni interpreta la salud: diagnosticar (`docker logs keycloak`); la base ya está verificada. Un converge lo reconcilia con su ciclo |
| Aparece un contenedor `<id>_postgres` o `<id>_keycloak` | `ESCALAR` | un recreate interrumpido deja el anterior renombrado: decidir cuál es el vigente antes de seguir |

El script nunca asume cuál de dos contenedores es el bueno.

## Paradas (escalar)

Código `3`, sin cambios, ante cualquiera de estas:

- Existe un contenedor `backend` (depende de la base; este procedimiento no lo contempla).
- Falta el contenedor de postgres o su volumen nombrado, o postgres no está `running` y `healthy`.
- Falta el contenedor de Keycloak: arrancarlo es del ciclo de converge, que clasifica la base
  (P1/P2/P3) antes. Desde aquí no se crea uno.
- Keycloak corre pero no está `healthy`, o está en un estado distinto de corriendo/detenido.
- Keycloak o postgres corren otra imagen que la de `image.env`.
- El esquema de Keycloak no existe o no tiene realms (no se arranca Keycloak sobre una base sin
  inicializar), o los roles de la base que declara el contenedor no existen.
- `daemon.json` no declara `json-file` con `max-size` y `max-file` (no hay objetivo).
- Un contenedor nuevo no hereda la rotación (sonda).
- Compose tiene un cambio de configuración pendiente en postgres o keycloak distinto de los logs (o su
  dry-run falla o no da evidencia): ver arriba.
- Hay otro proceso `docker compose`, o no se obtiene el lock en 60 s.
- Hay contenedores del proyecto con el servicio de postgres/keycloak y otro nombre.

## Respaldo

Un respaldo **lógico y mínimo**, proporcional a una instalación todavía sin datos de negocio: es una
red de seguridad para quien tenga que decidir una recuperación, no un sistema de recuperación.

- Contiene datos y credenciales (hashes): permisos restrictivos, nunca se copia fuera del host ni al
  repositorio, y no se imprime.
- Se comprueba que el archivo es legible (`pg_restore --list`) y que contiene el esquema de Keycloak
  con datos de `realm`. **Eso no prueba una restauración.** Nunca se ha restaurado.
- Este procedimiento **no restaura ni describe cómo restaurar**. La pérdida o corrupción de datos
  persistentes exige un procedimiento de recuperación **aparte y explícitamente autorizado**, que no
  forma parte ni es una continuación implícita de este script. Ante esa sospecha: parar, no repetir
  `apply`, conservar el volumen y el respaldo tal como están y escalar.
- Se conserva hasta que Run I verifique y después se borra a mano.

## Verificación manual (solo lectura)

Estado de logs y salud, sin volcar el entorno:

```bash
ssh.exe piedrazul-server-ops 'sudo docker inspect postgres keycloak --format "{{.Name}} log={{.HostConfig.LogConfig.Type}} opts={{json .HostConfig.LogConfig.Config}} health={{.State.Health.Status}}"'
```

Para el journal de Docker en una hora concreta, el host está en `America/Bogota`: se pasa la zona
explícita, o `journalctl` interpreta la hora como local (`-05:00`) y puede no devolver nada.

```bash
ssh.exe piedrazul-server-ops 'sudo journalctl -u docker.service --since "2026-11-12 18:40:00 UTC" --until "2026-11-12 18:55:00 UTC" --no-pager -o short-iso'
```

## Pruebas

- [`infra/tests/reconcile-container-logging-check.sh`](../../../infra/tests/reconcile-container-logging-check.sh):
  ensayo sobre un stack desechable con datos sintéticos (lectura sin efectos, autorización, volumen y
  datos intactos, omisión de lo conforme, repetición, continuación, paradas e inyección de fallos).
  No usa producción. Los valores por defecto del daemon se simulan con un override de Compose; lo que
  no puede probar es el daemon real del servidor ni Keycloak de verdad.
- [`infra/tests/log-config-check.sh`](../../../infra/tests/log-config-check.sh): la política de la
  verificación de logs efectivos.
