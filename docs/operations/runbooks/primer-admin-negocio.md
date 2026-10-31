# Primer administrador de negocio

## Propósito

Crear, en una instalación limpia de producción, la primera cuenta real con el rol `ADMIN` de
la aplicación, mediante un bootstrap **create-only** que se habilita explícitamente, se usa
una vez y se retira.

| | |
|---|---|
| Cuenta | usuario del realm `piedrazul`; `username` = número de documento, como el resto de cuentas |
| Autoridad | rol de realm `ADMIN` de `piedrazul` (autoridad de negocio, leída del token) |
| Registro | `Person` vinculada (`person.user_id` = id de la cuenta en Keycloak) |
| Credencial inicial | contraseña temporal del operador (`IDENTITY_SEED_ADMIN_PASSWORD`), un solo uso; el primer login exige cambiarla (`UPDATE_PASSWORD`) |

No es el administrador de la plataforma Keycloak (`piedrazul-platform-admin` en `master`, ver
[`administrador-keycloak.md`](administrador-keycloak.md)) ni ningún principal técnico
(`piedrazul-backend`, `piedrazul-keycloak-automation`, `piedrazul-tmp-admin-*`). El bootstrap
usa solo los permisos que el backend ya tenía (`manage-users` sobre `piedrazul`).

## Qué hace y qué nunca hace

Con `IDENTITY_SEED_ENABLED=true`, al arrancar el backend
([`AdminBootstrapService`](../../../backend/src/main/java/co/edu/unicauca/piedrazul/backend/user/application/bootstrap/AdminBootstrapService.java)):

1. Valida todas las entradas (documento según su tipo, nombres, correo, celular y las reglas
   de la contraseña temporal). Si algo falla no hace ninguna operación de identidad.
2. Toma un bloqueo consultivo de PostgreSQL: un arranque concurrente no clasifica ni escribe.
3. Clasifica, solo leyendo, la cuenta (por username y por correo), la `Person` (por documento
   y por correo) y la autoridad `ADMIN` existente en `piedrazul`.
4. Solo si no hay nada que adoptar: crea la cuenta **desactivada**, con correo no verificado
   (`emailVerified=false`), contraseña temporal y `UPDATE_PASSWORD`; asigna `ADMIN`; persiste la
   `Person` vinculada; relee y comprueba esos prerrequisitos; **recién entonces la activa** y
   vuelve a leer.

Nunca:

- adopta una cuenta, una `Person` o un correo existentes, ni crea un segundo `ADMIN` si ya hay
  uno;
- escribe sobre una identidad ya vinculada: no restituye un `ADMIN` retirado, no reactiva una
  cuenta desactivada, no restablece una contraseña cambiada, no toca sus datos;
- borra algo que no pueda probar propio y seguro (ver
  [Fallos parciales](#fallos-parciales-y-compensación));
- cambia la política de contraseñas del realm `piedrazul`;
- registra contraseñas, documentos, correos ni nombres.

Deshabilitado (por defecto) no hace ninguna operación de Keycloak ni de `Person` y no necesita
la contraseña.

Habilitado es **fail-closed**: el backend solo arranca con `CREATED` o `ALREADY_LINKED_READY`
(o con `ALREADY_LINKED_NOT_READY`, que no repara nada y hace fallar el despliegue); con
cualquier otro resultado sale con error y su política de reinicio lo reintenta.

## Entradas

Variables y secreto del GitHub Environment `production-hetzner`:

| Nombre | Tipo | Valor |
|---|---|---|
| `IDENTITY_SEED_ENABLED` | variable | `true` solo durante el bootstrap; `false` o sin definir el resto del tiempo |
| `IDENTITY_SEED_ADMIN_USERNAME` | variable | número de documento (será el username) |
| `IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE` | variable | `CEDULA`, `CEDULA_EXTRANJERIA`, `PASAPORTE`, `TARJETA_IDENTIDAD` o `REGISTRO_NACIMIENTO` |
| `IDENTITY_SEED_ADMIN_FIRST_NAME`, `IDENTITY_SEED_ADMIN_LAST_NAME` | variables | 2 a 60 letras, espacios, `'` o `-` |
| `IDENTITY_SEED_ADMIN_EMAIL` | variable | correo de la persona (no se marca verificado) |
| `IDENTITY_SEED_ADMIN_PHONE` | variable | celular de 10 dígitos |
| `IDENTITY_SEED_ADMIN_PASSWORD` | **secreto** | contraseña temporal |

Reglas de la contraseña temporal (las aplican el converge antes de escribir el `.env` y otra
vez el backend):

- al menos 16 caracteres; espacios internos y cualquier símbolo permitidos;
- sin espacios en los extremos, saltos de línea ni otros caracteres de control;
- sin el documento ni el correo (ni su parte local);
- distinta de toda credencial técnica (`KC_*_SECRET`, contraseñas de la base,
  `KC_PLATFORM_ADMIN_INITIAL_PASSWORD`) y de todo valor de `.env.example`.

Son reglas de esta entrada temporal, no la política de contraseñas del realm: no dicen nada de
la contraseña definitiva que la persona elige en el primer login.

## Procedimiento

### 1. Preparar

- Confirmar con la consola de Keycloak (cuenta de plataforma, realm `piedrazul` → *Realm
  roles* → `ADMIN` → *Users in role*) que no hay ninguna cuenta con `ADMIN`. Si la hay, el
  resultado será `CONFLICT existing_admin_authority`: decidir antes qué hacer con ella (fuera de
  este procedimiento).
- Generar la contraseña temporal en un gestor de contraseñas (larga y aleatoria) y acordar con
  la persona cómo se le entrega fuera de GitHub.

### 2. Desplegar primero una imagen compatible, con el bootstrap deshabilitado

Solo una imagen del backend que declara la etiqueta OCI
`org.piedrazul.identity-bootstrap=create-only-v1` (`backend/Dockerfile`) implementa este
bootstrap. Una imagen anterior procesaría `IDENTITY_SEED_ENABLED=true` con su propio código:
aprovisionamiento ordinario que adopta una cuenta existente y le reasigna roles, contraseña
permanente, y agendadores y auditor de demostración con contraseñas conocidas. Esos efectos
ocurrirían al arrancar, antes de cualquier verificación posterior.

Por eso, con el bootstrap habilitado, el despliegue **rechaza** una imagen sin esa etiqueta
antes de que pueda recibirlo: la configuración del servidor antes de escribir el `.env` (la
contraseña no llega al host) y el deploy antes de migrar o arrancar nada (también un rollback
a una imagen anterior). El converge solo recrea un backend que ya corre exactamente la imagen
seleccionada, la misma que verificó.

Procedimiento escalonado recomendado:

1. Con `IDENTITY_SEED_ENABLED` en `false` (o sin definir), desplegar normalmente una imagen
   construida desde esta versión, y confirmar que es la que corre (paso *Verificar identidad
   en ejecución* del workflow).
2. Recién entonces seguir con el paso siguiente. Un run que habilite el bootstrap y construya la
   imagen a la vez también es seguro (el converge no recrea la imagen anterior y el deploy
   arranca la nueva), pero el escalonado separa los dos cambios.

### 2b. Autorizar y habilitar

En `production-hetzner`: crear el secreto `IDENTITY_SEED_ADMIN_PASSWORD`, las variables
`IDENTITY_SEED_ADMIN_*` y poner `IDENTITY_SEED_ENABLED=true`.

### 3. Ejecutar

*Actions* → **Producción — Hetzner** → *Run workflow* con **Correr configuración del
servidor** y **Desplegar aplicación** activados. Solo el converge escribe el `.env` del host;
con el bootstrap habilitado, la verificación de credenciales exige todas las entradas y el
converge las valida antes de escribir nada.

El bootstrap corre cuando el backend arranca con esa configuración. **Fail-closed**: si el
resultado no es un bootstrap listo, el backend **no arranca** (sale con error y la política
`restart: unless-stopped` lo vuelve a intentar), salvo `ALREADY_LINKED_NOT_READY` (ver la
tabla). El paso que recrea el backend (converge o deploy) espera la línea
`identity-bootstrap result=` del arranque actual y **falla el run** salvo `CREATED` o
`ALREADY_LINKED_READY`; con un fallo, el run no llega a la salud pública ni registra el último
éxito.

Un resultado solo cuenta si el backend en ejecución tiene **en vigor** la configuración del
`.env`: sus `IDENTITY_SEED_*` son exactamente los que Compose le entregaría hoy. Según lo que
corra el workflow:

| Run | Qué pasa con el backend | Resultado del paso |
|---|---|---|
| configuración + deploy | el converge escribe `.env`; si el backend no corre la imagen seleccionada lo deja para el deploy (solo informa); el deploy lo recrea | el deploy exige: en vigor + `CREATED`/`ALREADY_LINKED_READY` (o retiro en vigor) |
| solo configuración | el converge recrea el backend si corre la imagen seleccionada | exige sobre el backend que corre; si no se recreó y el `.env` cambió, **falla**: cambio pendiente de recreación |
| solo deploy | usa el `.env` que dejó el último converge | **falla** si el `.env` no corresponde a `IDENTITY_SEED_ENABLED` del environment (habilitación o retiro pendientes de la configuración del servidor); si corresponde, exige como arriba |

### 4. Reconocer el resultado

La línea (en el log del paso y en `docker logs backend`) solo trae códigos e ids internos;
el paso muestra además el historial de resultados del contenedor (útil tras reinicios):

```
identity-bootstrap result=CREATED attempt=<uuid> stage=final_readback keycloak_user_id=<uuid> person_id=<uuid> admin_role=true email_verified=false enabled=true login_confirmed=no ...
```

| `result` | Significado | Backend | Run | Qué hacer |
|---|---|---|---|---|
| `CREATED` | cuenta nueva de este intento, vinculada, con `ADMIN`, activa; primer login pendiente | arranca | pasa | pasos 7 a 10 |
| `ALREADY_LINKED_READY` | la cuenta y la `Person` configuradas ya estaban vinculadas y se **confirmó** el estado del bootstrap: cuenta activa, `ADMIN` efectivo, ningún otro titular directo de `ADMIN` y el mismo tipo de documento; no se escribió nada. Diferencias de correo o nombres solo se informan (`configured_*_matches_*`). **No prueba** un login | arranca | pasa | seguir en 7 (o en 9 si ya se hizo) |
| `ALREADY_LINKED_NOT_READY` | ya vinculadas, pero no es el estado confirmado del bootstrap (`reasons`): `account_disabled`, `admin_role_missing`, `other_admin_holders` (hay otros titulares directos de `ADMIN`; sus ids internos en `other_admin_ids`) o `configured_identification_type_mismatch`. No se repara ni se presume: pueden ser decisiones administrativas posteriores | arranca (nada cambiaría al reiniciar) | **falla** | si fue deliberado o legítimo: paso 9; si no: inspección (paso 6) |
| `CONFLICT` | identidades o autoridad que no se adoptan (`reasons`); no se escribió nada | no arranca | falla | [inspección](#6-inspeccionar-un-estado-ambiguo-o-parcial) |
| `INVALID_INPUT` | entradas inválidas (`reasons` = nombres de variable y regla); no se leyó ni escribió nada | no arranca | falla | corregir y [reintentar](#5-reintentar) |
| `BUSY` | otro arranque tenía el bootstrap en curso; no se escribió nada | no arranca | falla | el reinicio lo reintenta |
| `FAILED_NO_CHANGES` | falló sin efectos conocidos: una lectura (también la observación de una identidad ya vinculada, `linked_observation_failed`) o Keycloak rechazó la creación | no arranca | falla | el reinicio lo reintenta |
| `FAILED_COMPENSATED` | falló tras crear la cuenta; la cuenta propia, desactivada y sin `Person`, se borró y se confirmó | no arranca | falla | el reinicio lo reintenta |
| `FAILED_NEEDS_INSPECTION` | estado parcial o incierto: puede existir una cuenta o `Person` de este intento | no arranca | falla | [inspección](#6-inspeccionar-un-estado-ambiguo-o-parcial) |

La salud del backend (`/actuator/health`) no dice nada de este resultado ni de un login.

Tras un `FAILED_NEEDS_INSPECTION`, el reinicio siguiente puede encontrar la identidad ya
vinculada y desactivada e informar `ALREADY_LINKED_NOT_READY account_disabled`: el historial
del paso muestra ambos. No activarla sin la inspección del paso 6.

### 5. Reintentar

Mientras el bootstrap esté habilitado y no termine listo, el backend se reinicia solo y cada
arranque es un intento nuevo, seguro por construcción (no adopta ni borra nada ajeno). Docker
reinicia sin espera acumulada si el contenedor alcanzó a correr unos segundos, así que el ritmo
es de un intento por arranque del backend.

Solo `FAILED_COMPENSATED` repetido deja rastro persistente: cada intento crea y borra su propia
cuenta desactivada y la auditoría registra, de forma inmutable, un `USUARIO_CREADO` (y un
`ROL_ASIGNADO` si el rol llegó a asignarse) sin registro del borrado, más sus publicaciones de
eventos archivadas. No envía notificaciones (ningún consumidor de esos eventos lo hace) y el
realm no registra eventos de Keycloak. Los demás resultados no escriben nada al reintentar. Con
un `FAILED_COMPENSATED` que se repite, detener los reinicios (ver abajo) antes de diagnosticar.

- `BUSY`, `FAILED_NO_CHANGES`, `FAILED_COMPENSATED`: esperar al reinicio o volver a correr el
  paso 3. No hace falta reconstruir Keycloak, la base ni el servidor.
- `INVALID_INPUT`: corregir las variables o el secreto y volver a correr el paso 3 (con
  configuración del servidor).
- Para detener los reinicios mientras se decide: `IDENTITY_SEED_ENABLED=false` y correr
  configuración + deploy (paso 9), o `docker stop backend` en el host.

### 6. Inspeccionar un estado ambiguo o parcial

Es **recuperación administrativa**, no reparación automática: la decide una persona después de
mirar el estado. Antes de tocar nada, poner `IDENTITY_SEED_ENABLED=false` y correr
configuración + deploy (o `docker stop backend`) para que ningún reinicio repita el intento
mientras se inspecciona.

Datos para la inspección: `attempt`, `stage`, `reasons` y `keycloak_user_id` de la línea; en
Keycloak (consola, realm `piedrazul` → *Users*): estado, *Created at*, *Required user actions*,
*Role mapping*; en la base, la `Person` con ese `user_id` o ese documento (consulta de solo
lectura con el rol de la aplicación).

| Situación (`reasons`) | Cómo leerla |
|---|---|
| `create_outcome_uncertain` | Keycloak no confirmó la creación. `account_found=true` no prueba que la cuenta sea de este intento |
| `person_outcome_uncertain` | la `Person` pudo confirmarse o no; la cuenta (desactivada) queda |
| `pre_enable_*`, `enable_failed_or_uncertain`, `final_readback_*`, `account_not_enabled` | la `Person` está confirmada; la cuenta puede estar desactivada o sin algún prerrequisito |
| `compensation_*`, `owned_account_missing` | no se pudo borrar o confirmar el borrado de la cuenta propia |
| `keycloak_account_without_person`, `person_exists_without_account*`, `person_linked_to_*`, `account_linked_to_other_person` | identidades previas que no coinciden con la configuración |
| `email_in_use_by_other_account`, `email_in_use_by_person` | el correo configurado ya lo usa otra identidad |
| `existing_admin_authority` | ya hay al menos una cuenta con `ADMIN` en `piedrazul` |
| `account_disabled`, `admin_role_missing` (`ALREADY_LINKED_NOT_READY`) | la identidad configurada es la del bootstrap y está vinculada; alguien la desactivó o le retiró `ADMIN`, o un intento anterior no llegó a activarla (ver el historial) |
| `other_admin_holders` (`ALREADY_LINKED_NOT_READY`) | además de la identidad del bootstrap hay otras cuentas con `ADMIN` directo (`other_admin_ids`). Pueden ser administradores creados después del primer login o autoridad previa no explicada: confirmar quiénes son antes de deshabilitar el bootstrap |
| `configured_identification_type_mismatch` (`ALREADY_LINKED_NOT_READY`) | la `Person` vinculada tiene el mismo número pero otro tipo de documento que el configurado: puede no ser la persona prevista |
| `linked_observation_failed` | no se pudo leer el estado de la identidad vinculada; no se informa como lista |

Reglas:

- **No borrar ni activar** una cuenta sin confirmar que es la de este bootstrap (mismo
  `keycloak_user_id` de la línea, creada en la hora del intento, username = documento
  configurado) y el estado de su `Person`.
- Solo una cuenta confirmada como de este bootstrap, **desactivada** y **sin `Person`
  vinculada** puede borrarse para volver a un estado limpio y reintentar.
- Si la cuenta confirmada como de este bootstrap ya tiene su `Person` vinculada, no borrar
  ninguna de las dos: completar a mano lo que falte (rol `ADMIN`, acción `UPDATE_PASSWORD`,
  activar) desde la consola, o escalar.
- Un `CONFLICT` con identidades que no son de este bootstrap no se resuelve borrándolas: cambiar
  las entradas o decidir el caso aparte.

### 7. Primer login

La persona entra a la aplicación con el documento y la contraseña temporal. Keycloak muestra
la página de cambio de contraseña (`UPDATE_PASSWORD`) antes de entregar la sesión; la nueva
contraseña reemplaza la temporal. El realm `piedrazul` no tiene política de contraseñas: se
recomienda una larga y única, guardada en un gestor.

### 8. Confirmar el acceso `ADMIN`

- La persona ve las funciones de administración de la aplicación con su sesión.
- En la consola: el usuario sin *Required user actions*, *Enabled*, y `ADMIN` en *Role mapping*.

Hasta aquí la línea `CREATED` o `ALREADY_LINKED_READY` no es prueba de nada de esto.

### 9. Deshabilitar el bootstrap y reconverger

1. Poner `IDENTITY_SEED_ENABLED=false` (o eliminar la variable).
2. Correr el workflow con **Correr configuración del servidor** y **Desplegar aplicación**.
   El converge reescribe el `.env` con todas las entradas vacías (aunque el secreto siga
   existiendo, no se lee), verifica que Compose ya no entregaría la contraseña y el backend
   se recrea sin ella. El paso **falla** si el contenedor resultante no refleja el `.env`
   (cambio pendiente de recreación) o todavía conserva `IDENTITY_SEED_ENABLED=true` o una
   contraseña inicial. Un run **solo de deploy** con la variable ya en `false` y el `.env`
   todavía habilitado falla también: el retiro necesita la configuración del servidor.
3. Comprobación manual opcional en el host (cuenta líneas, no muestra valores; ambas deben
   dar `0`):

   ```bash
   grep -c '^IDENTITY_SEED_ADMIN_PASSWORD=.' /opt/piedrazul/.env
   ```

   ```bash
   docker inspect backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -c '^IDENTITY_SEED_ADMIN_PASSWORD=.'
   ```

### 10. Eliminar el secreto y las entradas

En `production-hetzner`: eliminar el secreto `IDENTITY_SEED_ADMIN_PASSWORD` y las variables
`IDENTITY_SEED_ADMIN_*` (contienen datos personales). Con el bootstrap deshabilitado ningún
paso las exige, así que los converge y deploy siguientes no las necesitan ni las reintroducen.

## Fallos parciales y compensación

Keycloak y PostgreSQL no comparten transacción. Lo que el bootstrap hace ante cada fallo:

| Fallo | Efecto automático |
|---|---|
| creación incierta (timeout, 5xx, sin id) | nada: no hay prueba de propiedad |
| creación rechazada (4xx) o en conflicto (409) | nada: Keycloak no creó la cuenta |
| asignar `ADMIN` | borra la cuenta propia si sigue desactivada y sin `Person`; confirma el borrado |
| `Person` rechazada con certeza (duplicado, integridad) | igual que arriba |
| `Person` incierta (p. ej. fallo al confirmar) | nada |
| comprobación previa a activar, activar, relectura final | nada (la `Person` ya existe) |
| leer el estado de una identidad ya vinculada | nada; `FAILED_NO_CHANGES linked_observation_failed`, nunca "vinculada" |
| la compensación no se puede comprobar o falla | nada más; se informa `FAILED_NEEDS_INSPECTION` |

## Límites

- La autoridad `ADMIN` existente se busca entre los miembros directos del rol; una asignación
  vía grupos o roles compuestos no se enumera (el realm del repositorio no define ninguna).
- Una instalación que ya tenga cuentas con `ADMIN` (por ejemplo, creadas por versiones
  anteriores con datos de demostración) dará `CONFLICT existing_admin_authority`; este
  bootstrap no las reconcilia.
- Las identidades de demostración nunca se crean en producción: exigen el perfil `dev`
  exclusivo y `APP_DEMO_SEED_ENABLED=true`
  ([`DemoSeedCondition`](../../../backend/src/main/java/co/edu/unicauca/piedrazul/backend/shared/seed/DemoSeedCondition.java)).
  Deshabilitarlas no borra las ya persistidas.

## Desarrollo local

`.env.example` deja el bootstrap deshabilitado y sin contraseña. Los médicos de demostración
traen `ADMIN` y sirven como administradores en desarrollo. Para probar el bootstrap, habilitarlo
sobre una base y un realm limpios (sin datos de demostración, o se obtiene `CONFLICT`) con una
contraseña temporal propia.

## Validación

- Backend: `AdminBootstrapServiceTest` (estados, conflictos, fallos parciales),
  `AdminBootstrapInputValidatorTest`, `KeycloakUserClientBootstrapTest`,
  `IdentityDataInitializerTest`, `DemoSeedConditionTest`, `DemoStaffDataInitializerTest`;
  `AdminBootstrapPersistenceIT` (PostgreSQL desechable, `mvn verify`).
- Configuración y despliegue: `infra/tests/identity-seed-contract-check.sh`.
