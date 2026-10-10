# Administrador humano de Keycloak

## Propósito

Establecer y custodiar la cuenta humana permanente con autoridad completa sobre la plataforma
Keycloak de producción (realm `master`), para la administración excepcional y manual que la
automatización no hace.

| | |
|---|---|
| Cuenta | `piedrazul-platform-admin` en `master` (`kc_platform_admin_username` en `group_vars/all.yml`). Es un nombre de rol: no identifica a quien la custodia |
| Autoridad | rol de realm `admin` de `master` (`kc_platform_admin_role`): todos los realms, incluido `piedrazul` |
| Quién la usa | solo una persona operadora, a mano, desde la consola de administración. CI, Ansible y el backend nunca autentican con ella |
| Credenciales | contraseña y OTP del operador. La automatización solo conoce la contraseña **inicial**, temporal, y nunca la vuelve a usar |

No es el `ADMIN` de negocio de la aplicación (ver
[Roles de aplicación ADMIN y AUDITOR](#roles-de-aplicación-admin-y-auditor)), ni ninguno de
los principales técnicos (`piedrazul-keycloak-automation`, `piedrazul-backend`,
`piedrazul-tmp-admin-*`; ver `infra/ansible/ANSIBLE.md`, *Ciclo técnico de Keycloak*).

## Cuándo se crea y qué no hace la automatización

Solo durante una **instalación nueva** de Keycloak: la convergencia que encuentra la base de
Keycloak vacía (estado P2). Dentro de ese evento, con la autoridad temporal del propio evento
y después de dejar operativa la automatización:

1. Si `master` ya tiene un usuario con ese nombre, falla sin tocarlo (no lo adopta).
2. Fija la [política de contraseñas de `master`](#política-de-contraseñas) (`length(16)`) si
   `master` no tiene ninguna; si ya tiene otra distinta, falla sin pisarla.
3. Crea la cuenta **deshabilitada**, con las acciones requeridas `CONFIGURE_TOTP` y
   `UPDATE_PASSWORD`.
4. Fija la contraseña inicial como temporal y asigna el rol `admin`.
5. Lee de vuelta la cuenta y comprueba exactamente eso. Recién entonces la habilita.
6. Comprueba que la contraseña inicial llegó intacta y que por sí sola no da acceso (Keycloak
   responde *Account is not fully set up*).
7. Elimina la autoridad temporal, como en todo evento, también si algo de lo anterior falló.

Después la cuenta pertenece al operador. Ninguna convergencia posterior, rotación ni
recuperación P3 restablece su contraseña, borra, reemplaza o restituye su OTP, cambia sus
roles, atributos o estado, ni la recrea si se borró; tampoco reaplica la política de
contraseñas. Una convergencia solo **informa** su estado.

## Política de contraseñas

- Mínimo de **16 caracteres** para toda contraseña nueva o reemplazada: la inicial y la que
  el operador elige en el primer login o después. Keycloak rechaza una más corta
  (*Invalid password: minimum length 16*).
- **Sin** categorías obligatorias (mayúsculas, minúsculas, dígitos, símbolos) y **sin**
  rotación periódica. El hash es el de Keycloak por defecto (argon2).
- Recomendado: una contraseña larga, única y generada al azar, guardada en un gestor de
  contraseñas.
- Es una política **del realm `master`**, no solo de esta cuenta: vale para cualquier
  contraseña que se fije en `master` después de la instalación. No afecta a las contraseñas ya
  fijadas, a los clientes técnicos (autentican con secreto de cliente, no con contraseña) ni al
  realm `piedrazul`, cuyas políticas no cambian.
- Solo la fija la instalación nueva, sobre un `master` recién creado. Si después un operador
  la cambia desde la consola, las convergencias la respetan y la informan; no la reescriben.

## Credencial inicial

### Generarla y custodiarla

Quien va a operar la cuenta genera la contraseña en su máquina, por ejemplo con su gestor de
contraseñas o con:

```bash
openssl rand -base64 33
```

Requisitos que valida la convergencia antes de la instalación (sin mostrar el valor): al
menos 16 caracteres; sin saltos de línea ni espacios en blanco al principio o al final;
distinta de los valores actuales de `KC_BACKEND_CLIENT_SECRET` y
`KC_AUTOMATION_CLIENT_SECRET` y de los valores de referencia de `.env.example` (las variables
`*SECRET`, `*PASSWORD` y `*KEY`); y sin el nombre de la cuenta. No exige categorías de
caracteres: los caracteres especiales y los espacios intermedios son válidos y llegan
literales.

Guardarla en el gestor de contraseñas personal del operador. Nunca en el repositorio, issues,
chats ni archivos del servidor.

### Entregarla para una instalación nueva

Como secreto de **un solo uso** del environment `production-hetzner`:

```bash
gh secret set KC_PLATFORM_ADMIN_INITIAL_PASSWORD --env production-hetzner
```

(`gh` pide el valor por la terminal; no pasarlo con `--body`, que lo deja en el historial).

Recorrido: secreto del environment → paso `Correr converge` del job `production` (único paso
que lo recibe) → Ansible en el runner → en tránsito al `docker exec` de kcadm dentro del
contenedor `keycloak`. No se escribe en `.env`, Compose, el backend ni ningún archivo del
host, y todas las tareas que lo tocan llevan `no_log`.

Solo la instalación nueva lo exige. Si falta o no cumple los requisitos, la convergencia
falla **antes** del evento con `KEYCLOAK P2: … necesita KC_PLATFORM_ADMIN_INITIAL_PASSWORD
válida` y la base de Keycloak queda vacía: corregir el secreto y repetir. En una convergencia
normal (P1) no se usa ni se exige.

## Instalación nueva: mensajes esperados

En el log de `Correr converge`, además de los del ciclo técnico (`KEYCLOAK: estado P2`, la
autoridad temporal, `estado P1 después del evento`):

- `KEYCLOAK: administrador humano creado (admin de master, contraseña temporal, acciones requeridas UPDATE_PASSWORD y CONFIGURE_TOTP); …`
- `ADMINISTRACIÓN HUMANA DE KEYCLOAK (aparte del estado técnico): CREADA, primer login pendiente (…)`

Si en cambio aparece `el administrador humano NO quedó establecido`, ver
[Escalar](#escalar).

## Primer login

1. Abrir `https://auth.piedrazul.org/admin/master/console/`.
2. Ingresar `piedrazul-platform-admin` y la contraseña inicial.
3. Keycloak pide **configurar un autenticador**: escanear el código QR con una aplicación
   TOTP (por ejemplo Google Authenticator, Microsoft Authenticator, FreeOTP, Aegis o el
   gestor de contraseñas; TOTP, SHA1, 6 dígitos, 30 s), escribir el código que muestra y,
   opcionalmente, un nombre para el dispositivo.
4. Keycloak pide **una contraseña nueva** (mínimo 16 caracteres, sin otras reglas): elegirla
   larga y al azar y guardarla en el gestor de contraseñas.
5. Entra a la consola de administración. La contraseña inicial deja de valer.

Si el navegador se cierra a mitad, volver a entrar con la contraseña inicial: Keycloak retoma
las acciones pendientes.

## Eliminar el secreto inicial

Apenas termina el primer login:

```bash
gh secret delete KC_PLATFORM_ADMIN_INITIAL_PASSWORD --env production-hetzner
```

Mientras siga guardado, cada convergencia avisa `KC_PLATFORM_ADMIN_INITIAL_PASSWORD está
presente pero solo la usa una instalación nueva (P2)…`, sin usarlo.

## Segundo factor

- **Primer login**: el enrolamiento OTP es obligatorio (`CONFIGURE_TOTP`); sin él Keycloak no
  da acceso por ningún camino.
- **Después**: mientras la cuenta tenga un OTP enrolado, Keycloak lo pide en cada login, por
  navegador y por `admin-cli`. Mantenerlo es lo recomendado, pero queda bajo control del
  operador: puede retirarlo desde la consola de cuenta
  (`https://auth.piedrazul.org/realms/master/account/` → *Signing in*), como permite
  Keycloak.
- **Si se retira**: con los flujos por defecto de `master` (que este ciclo no modifica), el OTP
  es condicional y la contraseña sola vuelve a dar acceso. Es el comportamiento normal de
  Keycloak, no una falla; la convergencia lo informa como `SIN OTP` y no lo restituye. Para
  volver a usarlo: misma consola de cuenta → *Set up authenticator application*.

### Verificar el segundo factor

La convergencia confirma la **configuración** de la cuenta, no prueba un login. Después del
primer login, y cada vez que cambie el autenticador:

1. Cerrar sesión en la consola y volver a entrar con la contraseña nueva.
2. Comprobar que Keycloak pide el **código de un solo uso** antes de dar acceso.
3. En la siguiente convergencia (o un `run_host_config`), el estado debe ser `CONFIGURADA`.

## Leer el estado

Cada convergencia sobre una base inicializada informa, en el log de `Correr converge`, una
línea `ADMINISTRACIÓN HUMANA DE KEYCLOAK (aparte del estado técnico): …`. Es independiente de
P1/P2/P3: no hace fallar la convergencia ni es requisito del backend, y un P1 técnico no
significa que el administrador humano esté listo. No muestra el nombre de la cuenta ni ninguna
credencial: solo si está habilitada, si tiene el rol, cuántas contraseñas y OTP tiene, qué
acciones requeridas le quedan y la política de contraseñas vigente de `master`.

| Estado | Significado | Qué hacer |
|---|---|---|
| `CREADA, primer login pendiente` | contraseña temporal y acciones requeridas pendientes | completar el [primer login](#primer-login) |
| `CONFIGURADA` | habilitada, rol `admin`, contraseña propia, OTP enrolado, sin acciones pendientes | [verificar el segundo factor](#verificar-el-segundo-factor) a mano |
| `SIN OTP` | el operador retiró el OTP tras el primer login: la contraseña sola da acceso (ver [Segundo factor](#segundo-factor)) | ninguna obligatoria; para volver a usar OTP, consola de cuenta → *Set up authenticator application* |
| `INCOMPLETA` | existe pero deshabilitada, sin el rol o sin contraseña (creación interrumpida o cuenta alterada) | [escalar](#escalar) |
| `INESPERADA` | existe una cuenta con ese nombre que no es un usuario local permanente (service account, federada o temporal) | [escalar](#escalar) |
| `FALTA` | no hay cuenta con ese nombre en `master` | [escalar](#escalar) |

El estado describe lo que hay en la base, no su origen: una cuenta recreada a mano con el
mismo nombre y la misma configuración se ve igual que la original.

## Comportamiento comprobado y límites

Contra Keycloak 26.5.6 con los flujos por defecto de `master`:

- Con OTP enrolado, el login de navegador (consola de administración y de cuenta) pide el
  código después de la contraseña; un código incorrecto o reusado se rechaza.
- El otro camino de login de usuario habilitado en `master`, el *password grant* del cliente
  `admin-cli` (kcadm, API), también exige el código.
- Mientras quedan acciones requeridas, ninguno de los dos caminos da acceso.
- Sin OTP enrolado, los dos caminos aceptan solo la contraseña (ver
  [Segundo factor](#segundo-factor)). Cualquier `admin` de `master` también puede retirar el
  OTP de la cuenta.
- `master` sigue **sin protección contra fuerza bruta** (valor por defecto) y sin olvido de
  contraseña ni registro: la longitud y el azar de la contraseña son la barrera del primer
  factor. No hay lista de contraseñas prohibidas.

## Escalar

`FALTA`, `INESPERADA`, `INCOMPLETA`, una creación fallida (`el administrador humano NO quedó
establecido`, con el avance `platform_admin.*` y el error `error=platform_admin_*`), o la
pérdida de la contraseña o del dispositivo OTP **no tienen procedimiento automatizado**: no hay
recreación, reparación, cuenta de emergencia ni restablecimiento por la automatización.

Escalar al mantenedor de infraestructura con el mensaje del run. La convergencia técnica sigue
funcionando (P1) mientras tanto. La decisión de cómo restituir la administración humana queda
fuera de este procedimiento; en particular, una creación interrumpida deja la cuenta
**deshabilitada**, y la automatización no la habilita, completa ni borra.

## Roles de aplicación ADMIN y AUDITOR

La autoridad de plataforma (`admin` de `master`) y los roles de negocio son cosas distintas:

- `ADMIN`, `AUDITOR` y los demás roles de la aplicación son **roles de realm de `piedrazul`**
  y se asignan a usuarios de `piedrazul`. El backend los lee del token (`realm_access.roles`).
- La cuenta `piedrazul-platform-admin` vive en `master`: no inicia sesión en la aplicación y
  no debe recibir roles de negocio.

Asignación manual excepcional, con la cuenta de plataforma:

1. Consola de administración → selector de realm → `piedrazul`.
2. *Users* → elegir el usuario de negocio (ya existente en `piedrazul`).
3. *Role mapping* → *Assign role* → filtrar por roles de realm → `ADMIN` o `AUDITOR` →
   *Assign*.
4. El cambio vale desde el siguiente token del usuario (nuevo login o renovación).

Para quitarlo: mismo lugar, *Unassign*. Este camino solo cambia el rol en Keycloak: no pasa
por el backend, así que no crea ni actualiza registros de la aplicación asociados al usuario.
Las operaciones que dependan de esos registros siguen dependiendo de ellos. Es una
posibilidad operativa manual: cuando exista el aprovisionamiento del primer `ADMIN` de
negocio desde la aplicación, esta vía no se reemplaza automáticamente. No asignar roles de
negocio a service accounts (`service-account-*`).

## Validación

Ejercitado con `infra/tests/keycloak-lifecycle-harness.sh` contra PostgreSQL y Keycloak
26.5.6 desechables, con credenciales sintéticas: creación en P2 (también con una contraseña
inicial con `' " $ \ # &`, espacios, `{{ }}` y `{% %}`), exigencia de la contraseña antes del
evento, política `length(16)` de `master` (sin tocar la de `piedrazul`), primer login de
navegador real (OTP y cambio de contraseña: una de 14 caracteres rechazada, una de 16+ solo
minúsculas aceptada), logins siguientes con OTP por navegador y por `admin-cli`,
convergencias, rotaciones y recuperaciones sin cambios en la cuenta ni en la política,
colisión de nombre y política preexistente distinta sin adopción ni sobrescritura, OTP
retirado (acceso solo con contraseña, informado como `SIN OTP`), borrado sin recreación y
creación interrumpida (cuenta deshabilitada, informada como `INCOMPLETA`). No se ha ejecutado contra producción: el primer
login real y la verificación del segundo factor en producción los hace el operador.
