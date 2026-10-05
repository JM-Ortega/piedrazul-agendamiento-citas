# Recuperar el estado técnico de Keycloak (P3)

## Propósito

Devolver Keycloak de producción a un estado técnico conocido (P1) cuando la convergencia
normal falla cerrada con **P3 recuperable** (`KEYCLOAK P3 (desconocido/divergente)`). La
recuperación usa una autoridad temporal propia creada con `kc.sh bootstrap-admin service`,
reconcilia la automatización y el cliente backend, elimina su autoridad temporal (y huérfanas
inequívocamente propias de eventos anteriores) y verifica el resultado.

La recuperación **nunca** importa ni recrea `master`, `piedrazul` ni `piedrazul-backend`, no
borra identidades, no toca autoridad temporal ajena y no crea administradores permanentes. Sí
puede crear o corregir el cliente técnico `piedrazul-keycloak-automation` cuando falta o
diverge.

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
| `la automatización no ve el cliente piedrazul-backend` | roles de la automatización alterados |

## Casos no soportados: P3 BLOQUEANTE → escalar

Si el mensaje es `KEYCLOAK P3 BLOQUEANTE: …`, **no** hay automatización que lo corrija:
`keycloak_recovery=true` falla igual, antes de arrancar o tocar Keycloak, sin crear autoridad.

| Motivo | Por qué no se recupera solo |
|--------|-----------------------------|
| `falta el realm piedrazul` / `falta el realm master` | reimportarlo crearía un realm vacío en lugar del perdido: ocultaría pérdida de identidades |
| `falta el cliente piedrazul-backend en piedrazul` | recrearlo cambia la identidad del cliente; puede indicar una alteración mayor del realm |
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
  anteriores.
- Nadie más debe correr Ansible contra el servidor por fuera del workflow mientras tanto: la
  exclusión la da la concurrencia del workflow, no un lock del host.

## Impacto y riesgo

- **Keycloak se detiene** durante el evento (`bootstrap-admin` exige el servidor detenido):
  login y validación de tokens fallan hasta que vuelve a estar healthy (del orden de un minuto
  más el arranque). El backend sigue corriendo pero sus llamadas a Keycloak fallan mientras
  tanto.
- Se eliminan solo la autoridad temporal del evento y huérfanas inequívocamente propias.
- `piedrazul-keycloak-automation` queda con el secreto deseado y exactamente los roles
  `manage-clients` y `view-clients` de `piedrazul-realm`; roles adicionales se quitan.
- `piedrazul-backend` queda con la forma y los roles de service account del realm del
  repositorio; su secreto queda en `KC_BACKEND_CLIENT_SECRET` (el anterior deja de valer).
- No se tocan usuarios humanos de `master` (el run solo informa cuántos tienen rol `admin`)
  ni identidades de negocio.

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
   - `KEYCLOAK: automation.client=…, backend.shape=reconciled, …`;
   - `KEYCLOAK: autoridad temporal piedrazul-tmp-admin-… eliminada y rechazada por Keycloak`;
   - `KEYCLOAK: estado P1 después del evento`;
   - `KEYCLOAK P1: secreto del backend …; el backend autentica con él`.

## Verificar el resultado

- El run termina en éxito y el health público de Keycloak (discovery OIDC) pasa.
- Un `converge` normal posterior (sin `keycloak_recovery`) clasifica **P1** y no crea
  autoridad temporal. Hacerlo si la recuperación no vino acompañada de otro run.
- El backend opera con normalidad (`refresh_backend.yml` le entregó el `.env` vigente).

## Si falla

- **`P3 BLOQUEANTE`**: ver *Casos no soportados*; escalar.
- **`NO se pudo eliminar la autoridad temporal`**: Keycloak no estuvo disponible para la
  limpieza (p. ej. no arrancó a tiempo). La autoridad que quedó es propia e inutilizable (su
  secreto nunca se persistió: solo estuvo en tránsito durante el run). Revisar por qué Keycloak no arrancó; cuando esté
  healthy, volver a correr la recuperación: la reconoce como huérfana propia y la elimina.
- **`el evento recovery falló (…)` sin autoridad temporal propia en la base**: el estado quedó
  parcialmente reconciliado; el mensaje muestra el avance (`automation.*`, `backend.*`,
  `role.*`) y el error (`error=…`). Corregir la causa y repetir.
- **`el evento terminó … pero el estado es P3`**: algo sigue divergente (motivos en el
  mensaje). Si es bloqueante, escalar; si no, revisar antes de repetir.
- Un reintento de la recuperación es seguro: cada evento parte del estado observado, crea su
  propia autoridad temporal y elimina solo la propia.

## Validación

El procedimiento se ejercitó con estos mismos task files contra PostgreSQL y Keycloak 26.5.6
desechables (`infra/tests/keycloak-lifecycle-harness.sh`). Soportados: rotación de la
credencial de automatización (con caracteres especiales), escalada de roles, forma y roles del
backend alterados, recuperación interrumpida (huérfana propia) y recuperación que falla después
de reconciliar parcialmente. No soportados (bloquean también con recuperación, sin mutar):
autoridad temporal ajena, realm de la aplicación ausente, esquema parcial y base inicializada
fuera del ciclo. No se ha ejecutado contra producción.
