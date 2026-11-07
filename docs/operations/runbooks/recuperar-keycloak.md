# Recuperar el estado técnico de Keycloak (P3)

## Propósito

Devolver Keycloak de producción a un estado técnico conocido (P1) cuando la convergencia
normal falla cerrada con **P3 recuperable** (`KEYCLOAK P3 (desconocido/divergente)`). La
recuperación usa una autoridad temporal propia creada con `kc.sh bootstrap-admin service`,
reconcilia la automatización y el cliente backend, elimina su autoridad temporal (y huérfanas
inequívocamente propias de eventos anteriores) y verifica el resultado.

La recuperación **nunca** importa ni recrea `master`, `piedrazul` ni `piedrazul-backend`, no
borra identidades, no toca autoridad temporal ajena y no crea, repara ni modifica el
administrador humano permanente (eso solo ocurre en una instalación nueva; ver
[`administrador-keycloak.md`](administrador-keycloak.md)). Sí puede crear o corregir el
cliente técnico `piedrazul-keycloak-automation` cuando falta o diverge.

Cómo se clasifica el estado y qué hace cada paso: `infra/ansible/ANSIBLE.md`, sección
*Ciclo técnico de Keycloak*.

## Cuándo usarla: casos soportados (P3 recuperable)

El run falló en `Correr converge` con `KEYCLOAK P3 (desconocido/divergente): <motivos>` y el
mensaje sugiere `keycloak_recovery=true`:

| Motivo | Causa habitual |
|--------|----------------|
| `la credencial de automatización fue rechazada` | se rotó `KC_AUTOMATION_CLIENT_SECRET` en GitHub (la automatización no puede cambiarse a sí misma) |
| `autoridad temporal propia de un evento interrumpido` | un evento P2 o de recuperación anterior se interrumpió antes de limpiar (clientId `piedrazul-tmp-admin-<fecha>-<aleatorio>` con solo los roles de bootstrap-admin) |
| `falta el cliente de automatización … en master` | se borró `piedrazul-keycloak-automation` |
| `roles … divergentes` / `forma … divergente` | cambios manuales sobre `piedrazul-keycloak-automation` o `piedrazul-backend` |
| `alcance de roles de <cliente> divergente: Full Scope Allowed habilitado; faltan …; sobran …` | cambios manuales en *Client scopes → Dedicated scope → Scope* de `piedrazul-keycloak-automation`, `piedrazul-backend` o `piedrazul-frontend` (ver [Alcance de roles de los clientes propios](#alcance-de-roles-de-los-clientes-propios)) |
| `la automatización no ve el cliente piedrazul-backend` | roles de la automatización alterados |
| `alcance kc-admin-api de <cliente> divergente: <actual> (esperado: default\|none)` | actualización de una base anterior a Keycloak 26.8.2 (Keycloak crea el scope pero no lo asigna a los clientes propios) o cambio manual en *Clients → <cliente> → Client scopes* (ver [API de administración: kc-admin-api](#api-de-administración-kc-admin-api)) |

## Casos no soportados: P3 BLOQUEANTE → escalar

Si el mensaje es `KEYCLOAK P3 BLOQUEANTE: …`, **no** hay automatización que lo corrija:
`keycloak_recovery=true` falla igual, antes de arrancar o tocar Keycloak, sin crear autoridad.

| Motivo | Por qué no se recupera solo |
|--------|-----------------------------|
| `falta el realm piedrazul` / `falta el realm master` | reimportarlo crearía un realm vacío en lugar del perdido: ocultaría pérdida de identidades |
| `falta el cliente piedrazul-backend en piedrazul` / `falta el cliente piedrazul-frontend en piedrazul` | recrearlo cambia la identidad del cliente; puede indicar una alteración mayor del realm |
| `esquema de Keycloak parcialmente inicializado (N objetos, sin tabla realm)` | inicialización interrumpida o ajena; no se distingue de forma segura de datos dañados |
| `falta el schema keycloak` | el contrato de la base (P-1) está roto |
| `autoridad temporal ajena o ambigua presente` | un administrador temporal que este ciclo no creó (otro clientId, un usuario, o roles distintos de los de bootstrap-admin): nunca se adopta ni se borra |

Escalar al mantenedor de infraestructura con el mensaje completo del run. La decisión (por
ejemplo restaurar la base desde respaldo, reiniciar el schema de Keycloak de una instalación
que nunca tuvo identidades, o retirar una autoridad ajena tras confirmar su origen) queda fuera
de esta recuperación. Una vez resuelta, una convergencia normal vuelve a clasificar.

En una primera instalación interrumpida (sin usuarios todavía) el resultado típico es
`esquema … parcialmente inicializado` o `falta el realm piedrazul`: también se escala; el
ciclo no decide por sí mismo descartar el estado.

## Requisitos y autorización

- Permiso para lanzar `workflow_dispatch` de **Producción — Hetzner** sobre `main` y ser (o
  conseguir) revisor del environment `production-hetzner`. La recuperación es el mismo job
  `production`: misma aprobación, misma concurrencia (`piedrazul-production-hetzner`, en cola,
  sin cancelar) y misma comprobación de frescura de `main`.
- Los secretos `KC_BACKEND_CLIENT_SECRET` y `KC_AUTOMATION_CLIENT_SECRET` con los valores que
  deben quedar vigentes (si se está rotando, el nuevo ya guardado).
- No hace falta SSH, ni credenciales de administración de Keycloak, ni conocer secretos
  anteriores. `KC_PLATFORM_ADMIN_INITIAL_PASSWORD` no interviene.
- Nadie más debe correr Ansible contra el servidor por fuera del workflow mientras tanto: la
  exclusión la da la concurrencia del workflow, no un lock del host.

## Impacto y riesgo

- **Keycloak se detiene** durante el evento (`bootstrap-admin` exige el servidor detenido):
  login y validación de tokens fallan hasta que vuelve a estar healthy (del orden de un minuto
  más el arranque). El backend sigue corriendo pero sus llamadas a Keycloak fallan mientras
  tanto.
- Se eliminan solo la autoridad temporal del evento y huérfanas inequívocamente propias.
- `piedrazul-keycloak-automation` queda con el secreto deseado y exactamente los roles
  `manage-clients`, `manage-realm` y `view-clients` de `piedrazul-realm`; roles adicionales se
  quitan. Un realm instalado antes de `manage-realm` necesita esta recuperación una vez
  ([correo-ses.md](correo-ses.md#correo-nativo-de-keycloak-y-recuperación-de-contraseña)).
- `piedrazul-backend` queda con la forma y los roles de service account del realm del
  repositorio; su secreto queda en `KC_BACKEND_CLIENT_SECRET` (el anterior deja de valer).
- No se tocan usuarios humanos de `master` (el run solo informa cuántos tienen rol `admin` y
  el estado del administrador humano permanente) ni identidades de negocio.

## Procedimiento

1. Leer el motivo en el log del paso `Correr converge`. Si dice `P3 BLOQUEANTE`, no seguir:
   escalar.
2. Si se está rotando `KC_AUTOMATION_CLIENT_SECRET`: confirmar que el valor nuevo ya está
   guardado en GitHub.
3. Actions → **Producción — Hetzner** → *Run workflow* sobre `main` con:
   - `run_host_config`: **true**
   - `keycloak_recovery`: **true**
   - `run_app_deploy`, `run_build`, `run_build_postgres`, `run_terraform_apply`: **false**
     (salvo que también se quiera desplegar; mantener la recuperación sola es lo más claro)

   La clasificación rechaza `keycloak_recovery=true` sin `run_host_config=true`. El resumen de
   *Intención de producción* muestra la recuperación como **autorizada**.
4. Aprobar el gate del environment `production-hetzner` solo si el run es el esperado.
5. Seguir el paso `Correr converge`. Mensajes esperados en orden:
   - `KEYCLOAK: estado P3` con los motivos (ninguno bloqueante);
   - `KEYCLOAK: evento de recuperación P3 autorizada con autoridad temporal piedrazul-tmp-admin-…`;
   - `KEYCLOAK: automation.client=…, backend.shape=reconciled, …, scope.<cliente>=updated|unchanged` (uno por cliente propio);
   - `KEYCLOAK: autoridad temporal piedrazul-tmp-admin-… eliminada y rechazada por Keycloak`;
   - `KEYCLOAK: estado P1 después del evento`;
   - `KEYCLOAK P1: secreto del backend …; el backend autentica con él`;
   - `KEYCLOAK SMTP: …`, según `NOTIFICATIONS_SES_ENABLED`.

## Verificar el resultado

- El run termina en éxito y el health público de Keycloak (discovery OIDC) pasa.
- Un `converge` normal posterior (sin `keycloak_recovery`) clasifica **P1** y no crea
  autoridad temporal. Hacerlo si la recuperación no vino acompañada de otro run.
- El backend opera con normalidad (`refresh_backend.yml` le entregó el `.env` vigente).
- Si el motivo era el alcance de roles: el run muestra `scope.<cliente>=updated` para los
  clientes corregidos y el converge siguiente es P1 (la clasificación vuelve a comparar Full
  Scope Allowed y las asignaciones de los tres clientes). Usuarios con sesión abierta ven el
  cambio en su siguiente token.

## Si falla

- **`P3 BLOQUEANTE`**: ver *Casos no soportados*; escalar.
- **`NO se pudo eliminar la autoridad temporal`**: Keycloak no estuvo disponible para la
  limpieza (p. ej. no arrancó a tiempo). La autoridad que quedó es propia e inutilizable (su
  secreto nunca se persistió: solo estuvo en tránsito durante el run). Revisar por qué Keycloak no arrancó; cuando esté
  healthy, volver a correr la recuperación: la reconoce como huérfana propia y la elimina.
- **`el evento recovery falló (…)` sin autoridad temporal propia en la base**: el estado quedó
  parcialmente reconciliado; el mensaje muestra el avance (`automation.*`, `backend.*`,
  `role.*`, `scope.*`) y el error (`error=…`; p. ej. `scope_role:realm:<rol>` si el realm no
  tiene un rol que el repositorio declara en un alcance). Corregir la causa y repetir. El
  alcance se escribe por cliente en orden seguro (agregar lo que falta → deshabilitar Full
  Scope Allowed → quitar lo que sobra → releer). Ese orden no crea por sí mismo un cliente
  con Full Scope deshabilitado y roles faltantes: si el cliente partía con su autoridad
  completa, una interrupción lo deja con al menos esa autoridad. Si ya partía de un estado
  defectuoso (p. ej. Full Scope deshabilitado sin sus roles), puede seguir sin permisos
  suficientes hasta que la recuperación termine. Repetirla parte del estado leído, completa lo
  pendiente y no cambia lo que ya es correcto.
- **`el evento terminó … pero el estado es P3`**: algo sigue divergente (motivos en el
  mensaje). Si es bloqueante, escalar; si no, revisar antes de repetir.
- Un reintento de la recuperación es seguro: cada evento parte del estado observado, crea su
  propia autoridad temporal y elimina solo la propia.

## Alcance de roles de los clientes propios

Los tres clientes propios tienen *Full Scope Allowed* deshabilitado y un alcance de roles
explícito: sus tokens solo llevan esos roles (si el usuario o el service account los tiene).

| Cliente | Realm | Alcance | Se declara en |
|---------|-------|---------|---------------|
| `piedrazul-backend` | `piedrazul` | `realm-management`: `view-realm`, `view-users`, `query-users`, `manage-users`, `view-events` (= sus roles asignados) | realm del repositorio (`clientScopeMappings`) |
| `piedrazul-frontend` | `piedrazul` | roles de realm `ADMIN`, `SCHEDULER`, `DOCTOR`, `PATIENT`, `AUDITOR` | realm del repositorio (`scopeMappings`) |
| `piedrazul-keycloak-automation` | `master` | `piedrazul-realm`: `manage-clients`, `manage-realm`, `view-clients` (= `kc_automation_roles`) | `kc_automation_roles` |

- Lo establece el evento con autoridad temporal (`keycloak-admin.sh establish`): en P2 el
  realm importado ya trae backend y frontend; la automatización, que el evento crea, se ajusta
  ahí. La convergencia P1 solo lo compara; nunca lo escribe.
- Los clientes integrados de Keycloak (`admin-cli`, `security-admin-console`, la autoridad
  temporal) no cambian y no hay política global de clientes.
- Un rol de negocio nuevo exige agregarlo **también** al alcance del frontend en el realm del
  repositorio (y al enum `Role` del backend): si no, no llega al token y ni Angular ni Spring
  lo ven. Converge falla antes de tocar Keycloak si los roles de realm y el alcance del
  frontend no coinciden. Un realm ya inicializado no recibe roles nuevos del archivo: ese
  cambio requiere su propia decisión.
- Revertir el código no revierte Keycloak: un commit anterior no lee el alcance ni lo escribe,
  y el realm conserva el que tenga.

## API de administración: kc-admin-api

Desde Keycloak 26.8.2 (CVE-2026-19688) la API de administración solo acepta tokens que traen
**a la vez** la audiencia `<URL pública>/admin/realms/<realm>` y el alcance del client scope
incorporado `kc-admin-api`; si no, responde **401** aunque el cliente autentique y tenga sus
roles. Keycloak crea ese scope en cada realm (también en el importado en P2, después de sus
clientes: una referencia en el JSON del realm se ignora) y solo lo asigna a `admin-cli`,
`security-admin-console` y la autoridad temporal de `bootstrap-admin`.

| Cliente | Realm | `kc-admin-api` |
|---------|-------|----------------|
| `piedrazul-keycloak-automation` | `master` | por defecto |
| `piedrazul-backend` | `piedrazul` | por defecto |
| `piedrazul-frontend` | `piedrazul` | nunca (ni por defecto ni opcional) |

- Por defecto y no opcional: kcadm y el cliente de administración del backend no piden
  alcances. El scope no trae roles: la autoridad sigue siendo exactamente la de sus roles
  (fuera de ella, 403).
- Lo asigna el evento con autoridad temporal (`keycloak-admin.sh establish`, salida
  `admin_api.<cliente>=updated|unchanged|removed|absent`), que termina con una operación real de
  la API como automatización (`automation.admin_api=ok`). La convergencia P1 solo lo compara
  (desde la versión `kc_admin_api_scope_since` que registra la base); nunca lo escribe.
- **Nunca** se deshabilita la verificación de audiencia de Keycloak ni se usa un scope propio
  con la misma audiencia (Keycloak lo rechaza igual: exige el scope `kc-admin-api`).
- Desarrollo (`docker-compose.yml`): `keycloak-dev-secret` lo asigna por defecto al backend.

**Actualizar una base anterior a 26.8.2**: la convergencia normal migra la base y después
falla cerrada como P3 recuperable (`alcance kc-admin-api de piedrazul-keycloak-automation …` y
`… de piedrazul-backend …`). Hasta la recuperación, backend y automatización reciben 401 de la
API. La automatización no puede corregirse a sí misma (su token ya es rechazado): correr la
recuperación autorizada de este runbook. Una instalación nueva (P2) en 26.8.2 lo deja
establecido sin pasos adicionales.

## Actualización de la versión de Keycloak

Cambiar de versión **no es una recuperación** ni un evento con autoridad temporal: es una
convergencia normal con otra imagen. Keycloak migra su base al arrancar y esa migración es
**solo hacia adelante**.

**Cómo se hace**: se reemplaza la referencia fijada en `infra/compose/keycloak.yml` y en
`docker-compose.yml` (`keycloak-dev-secret`) por `quay.io/keycloak/keycloak:<versión>@sha256:<índice OCI>`
(el índice se lee con `docker buildx imagetools inspect quay.io/keycloak/keycloak:<versión>`,
campo `Digest`) después de revisar las notas de actualización de Keycloak, y se despliega por
la cadena normal: un cambio en `infra/compose` corre converge. El workflow resuelve ese índice
a su manifiesto `linux/amd64`, lo escanea, la política de vulnerabilidades lo autoriza y converge despliega exactamente ese
manifiesto (ver [`ANSIBLE.md`](../../../infra/ansible/ANSIBLE.md#identidad-de-keycloak-keycloak_imageyml)).

**Qué hace converge** (`keycloak_lifecycle.yml`), antes de tocar Keycloak:

- comprueba que Compose arrancaría exactamente la identidad recibida y que la versión de la
  imagen coincide con el tag declarado;
- lee la versión que migró la base por última vez (`migration_model`) e informa
  `KEYCLOAK: base migrada por <anterior>, arrancar <nueva> la migra hacia adelante (sin vuelta atrás)`;
- recrea Keycloak con la imagen nueva (`Levantar Keycloak sobre la base ya inicializada`) y
  sigue la clasificación normal (P1, sin autoridad temporal; al cruzar 26.8.2, P3 recuperable
  hasta la recuperación: ver [API de administración: kc-admin-api](#api-de-administración-kc-admin-api));
- al final verifica que el contenedor corre esa identidad exacta y que la base quedó en la
  versión nueva (`KEYCLOAK <versión> en ejecución (…); base en <versión>`).

**Sin vuelta atrás**: si la base ya fue migrada por una versión posterior a la imagen del
converge, falla cerrado —también con `keycloak_recovery=true`— sin detener, recrear ni arrancar
Keycloak:

```
KEYCLOAK: la base ya fue migrada por Keycloak <posterior> y la imagen de este converge es <anterior> (…).
Volver atrás sobre una base migrada no está soportado
```

Revertir en `main` el commit que cambió la versión no restaura la anterior: el converge de ese
revert falla así. Una actualización fallida se corrige hacia adelante (la misma versión o una
posterior). La automatización no toma respaldos de la base de Keycloak: volver al estado
anterior a una migración solo es posible restaurando un respaldo tomado antes, fuera de esta
cadena.

**Si Keycloak no arranca o no migra**: converge falla en `Levantar Keycloak sobre la base ya
inicializada` (espera hasta `kc_start_timeout`, 600 s) y el workflow no llega a la verificación
ni al registro. Revisar `docker logs keycloak`. La migración pudo quedar aplicada en parte:
no arrancar la imagen anterior; corregir la causa y repetir converge con la misma versión.

**Lo que no cambia Keycloak**: un deploy sin converge (p. ej. solo backend, o el rollback
explícito del backend) nunca arranca, recrea ni actualiza Keycloak. Exige, antes de tocar nada,
que `keycloak` corra `healthy` exactamente la `KEYCLOAK_IMAGE` de `image.env`; si no (por
ejemplo, un converge de actualización interrumpido dejó en `image.env` la versión nueva y sigue
corriendo la anterior, o Keycloak está detenido), falla sin cambios: repetir converge.

**Versión de la base desconocida**: si la base de Keycloak está inicializada pero no registra
una versión conocida (`migration_model` ausente, vacía, con versión nula o sin forma `X.Y.Z`),
converge falla antes de arrancar Keycloak, también con `keycloak_recovery=true`:

```
KEYCLOAK: la base está inicializada pero su versión registrada (migration_model) es desconocida (…)
```

No se repara automáticamente: escalar (la base fue alterada fuera de Keycloak).

## Validación

El procedimiento se ejercitó con estos mismos task files contra PostgreSQL y Keycloak
desechables (`infra/tests/keycloak-lifecycle-harness.sh`): 26.5.6 y 26.8.1 desde una base
nueva, y 26.8.1 sobre una base creada por 26.5.6 y migrada por converge
(`HARNESS_TARGET=upgrade`). Con 26.8.2 se ejecutaron de nuevo el conjunto por defecto (base
nueva) y `HARNESS_TARGET=upgrade` (26.5.6 → 26.8.2), todo PASS: kc-admin-api establecido en P2
(automatización y backend por defecto, frontend sin él, sin roles), tokens de usuario del
frontend sin el scope ni la audiencia de la API, un cliente nuevo rechazado con 401, divergencia
en los tres clientes como P3 recuperable restablecida por la recuperación existente, y la
actualización que cruza 26.8.2 fallando cerrada como P3 recuperable hasta la recuperación
autorizada (después P1 sin evento). Los objetivos `migration-version` y `smtp` no se
repitieron con 26.8.2. Soportados: rotación de la
credencial de automatización (con caracteres especiales), escalada de roles, forma y roles del
backend alterados, recuperación interrumpida (huérfana propia) y recuperación que falla después
de reconciliar parcialmente. No soportados (bloquean también con recuperación, sin mutar):
autoridad temporal ajena, realm de la aplicación ausente, esquema parcial y base inicializada
fuera del ciclo. Actualización 26.5.6 → 26.8.1 por converge normal: P1 sin evento, base migrada,
clientes técnicos (secretos, forma y roles), administrador humano con OTP e identidades de
negocio pendientes de su primer login sin cambios; un converge con la imagen anterior sobre la
base migrada falla cerrado, también con `keycloak_recovery=true`, sin tocar Keycloak; una
actualización interrumpida (Keycloak sin llegar a healthy en el plazo) falla sin informar
éxito y el converge siguiente termina. Una base inicializada sin versión registrada conocida
(tabla ausente, vacía, versión nula o sin forma de versión) falla cerrado, también con
recuperación, sin tocar Keycloak (`HARNESS_TARGET=migration-version`). Un deploy sin converge
con Keycloak ausente, detenido, con otra identidad que la de `image.env` (también tras un
converge interrumpido que la adelantó) o con `KEYCLOAK_IMAGE` ausente, duplicada o malformada
falla sin escribir `image.env` ni tocar contenedores. No se ha ejecutado contra producción.
