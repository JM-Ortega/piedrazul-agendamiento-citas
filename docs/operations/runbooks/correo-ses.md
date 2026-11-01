# Correo por Amazon SES

## Propósito

Configurar, cambiar y retirar el correo por el SMTP de Amazon SES en producción. Lo usan el backend
(códigos y recordatorios) y Keycloak (recuperación de contraseña). La instalación inicial no lo
necesita: **sin ninguna configuración de SES** se completan Run P, Run I, la configuración del
servidor y los deploys ordinarios.

Este runbook describe la configuración. El comportamiento del proveedor (STARTTLS, plazos, circuit
breaker, clasificación de respuestas) está en
[despacho de notificaciones](../../architecture/overview.md#despacho-de-notificaciones).

## Qué se puede hacer hoy y qué falta

| Disponible en el repositorio | Pendiente, fuera del repositorio |
|---|---|
| Instalar y operar con SES deshabilitado | Identidad y dominio verificados en SES |
| Validar decisión, endpoint y credenciales antes de mutar | Registros DNS del remitente (DKIM, SPF, DMARC, MAIL FROM) |
| Comprobar que la imagen trae el proveedor SES | Región definitiva y salida de sandbox |
| Entregar la configuración al backend y compararla sin mostrarla | Usuario IAM y credenciales SMTP |
| Detectar configuración obsoleta en el host o en el contenedor | Aprobación para enviar a personas reales |
| Configurar el SMTP nativo de Keycloak y su recuperación de contraseña, y retirarlos | |

Hasta que no se haya hecho un envío real, nada de esto demuestra que el correo se entregue.
Tampoco se ha comprobado en un run real de producción que un job con environment reciba estas
variables como se espera; la primera activación debe confirmarlo.

## De dónde sale cada valor

Todo vive en el GitHub Environment `production-hetzner`:

| Entrada | Tipo | Cuándo se exige |
|---|---|---|
| `NOTIFICATIONS_SES_ENABLED` | variable | opcional: `true` habilita; `false` o ausente deshabilita; cualquier otro valor (también `TRUE`) falla |
| `SES_SMTP_HOST` | variable | solo habilitado |
| `SES_SMTP_USERNAME` | secreto | solo habilitado |
| `SES_SMTP_PASSWORD` | secreto | solo habilitado |

- **Las dos variables no pueden existir en el repositorio, la organización ni otro environment**,
  ni siquiera con valor `false`: `vars` no distingue el origen y una copia podría decidir por el
  environment. El job `classify`, que no tiene environment, busca esos nombres en `vars` y todo run
  que vaya a tocar el servidor falla antes del Apply y de conectarse. Una copia en otro environment
  no se ve desde `classify`; la detecta `audit-github-trust.sh`, igual que toda copia por nombre,
  cualquiera que sea su valor.
- **Copias con valor vacío.** La documentación de GitHub no dice si una variable puede quedar vacía
  ni si aparecería entonces en `vars`, y esto no se ha observado en un run real. La auditoría es la
  comprobación autoritativa de esas copias. Su efecto es acotado: el environment tiene precedencia,
  y si no define la variable, vacío significa lo mismo que ausente: deshabilitado.
- `SES_SMTP_HOST` debe ser `email-smtp[-fips].<región>.amazonaws.com` o
  `email-smtp[-fips].<región>.api.aws`. Se comprueba la forma, no que SES exista en esa región.
- Las credenciales deben ser ASCII imprimible, sin espacios.
- El remitente no es una entrada: `notifications_email_from` y `notifications_email_from_name` están
  en `group_vars`.
- El puerto (587), los plazos y el circuit breaker usan los valores por defecto de la aplicación.
  `NOTIFICATIONS_CONSOLE_SHOW_SENSITIVE` no es una entrada de producción.

Recorrido: environment → `ses-config.sh` (preflight) → `ses_inputs.yml` → `.env` → Compose → **solo**
el contenedor `backend`. Ni `migrate` ni los demás servicios reciben estas variables. Keycloak
tampoco: el converge le aplica la configuración por su API, sin reiniciarlo
([correo nativo de Keycloak](#correo-nativo-de-keycloak-y-recuperación-de-contraseña)).

Solo dos pasos de `production` pueden leer los secretos, y solo con SES habilitado: el preflight
(si el run incluye la configuración del servidor) y el converge. El paso de deploy nunca los recibe.

## SES deshabilitado

Es el estado por defecto. Con la variable ausente o en `false`:

- no se exige ni se lee ningún secreto de SES, y no hacen falta valores de relleno;
- `.env` lleva `NOTIFICATIONS_SES_ENABLED=false` y endpoint y credenciales vacíos, aunque los
  secretos sigan guardados en el environment;
- el backend arranca sin proveedor real y `/actuator/health` no depende del correo;
- no se inspecciona la imagen, y un rollback no tiene esta comprobación;
- los códigos responden `503` (`VERIFICATION_DELIVERY_UNAVAILABLE`) y no se emite ninguno;
- nadie recibe los recordatorios de cita. Uno vigente termina en un intento de consola `FAILED`
  (`CONSOLE_DIAGNOSTIC_ONLY`), y sin el diagnóstico sintético, que producción no activa, el log solo
  muestra `[contenido sensible oculto]` y el destinatario enmascarado. Otros resultados posibles, sin
  llegar a la consola: `CANCELLED` si la cita ya no está agendada, y `FAILED` sin intentos para un
  código que ya estuviera en cola, por ejemplo tras deshabilitar SES
  ([despacho de notificaciones](../../architecture/overview.md#despacho-de-notificaciones));
- el realm `piedrazul` no tiene SMTP y la recuperación de contraseña está apagada: el login no ofrece
  el enlace y Keycloak rechaza la ruta. El primer administrador de negocio define su contraseña por
  `UPDATE_PASSWORD`, sin correo ([primer-admin-negocio.md](primer-admin-negocio.md)).

## Correo nativo de Keycloak y recuperación de contraseña

`NOTIFICATIONS_SES_ENABLED` es el único interruptor: **habilitar SES también habilita la recuperación
nativa de contraseña** de todas las cuentas del realm `piedrazul`, y deshabilitarlo la apaga. No hay
otra entrada: Keycloak usa el mismo endpoint, las mismas credenciales y el remitente de `group_vars`.

Lo aplica cada converge al final del ciclo de Keycloak (`keycloak_smtp.yml`), solo en P1, con la
autoridad de automatización y una sola escritura parcial del realm: SMTP y recuperación cambian
juntos o no cambian. El puerto es 587 con STARTTLS obligatorio. El realm `master` no se toca, y
Keycloak no se reinicia.

| Con SES | Qué hace el converge | Resultado |
|---|---|---|
| ausente o `false`, realm limpio | nada: ni llamada a la API ni conexión SMTP | sin SMTP, recuperación apagada |
| ausente o `false`, realm con SMTP de SES o recuperación encendida | limpia | sin SMTP, recuperación apagada |
| `true` | escribe la configuración completa en **cada** converge: Keycloak enmascara la contraseña y una rotación no se puede detectar comparando | SMTP aplicado, recuperación encendida |
| cualquiera, realm con un host que no es de SES | falla sin escribir | SMTP ajeno intacto |

La configuración SMTP del realm es de la automatización: un cambio manual a un endpoint de SES se
sobrescribe en el converge siguiente. Un host que no es de SES no lo puso este ciclo y hay que
decidir sobre él en la consola de administración antes de repetir el converge.

**Realm anterior a este contrato.** Escribir SMTP exige `manage-realm`, un rol que un realm instalado
antes no tiene. Su converge normal da P3 (`roles del service account de automatización divergentes`)
y no toca nada: hay que correr una vez la
[recuperación autorizada](recuperar-keycloak.md), que detiene Keycloak por un momento, agrega el rol
y aplica el correo en el mismo run. Una instalación nueva ya nace con él.

### Riesgo de la dirección de correo

El enlace de recuperación va a la dirección guardada en la cuenta. **Keycloak no comprueba que esa
dirección sea de quien usa la cuenta**: lo envía aunque el correo no esté verificado, y el proyecto
no verifica la propiedad del correo (las cuentas pueden tener `emailVerified=true` sin que nadie lo
haya confirmado). Si la dirección es incorrecta o la controla un tercero, esa persona recibe un
enlace válido para fijar la contraseña de la cuenta. Quien conozca un documento puede además provocar
el envío, y no hay límite de solicitudes propio del proyecto.

Conviene tenerlo presente antes de habilitar SES y corregir las direcciones conocidas como erróneas.
Una cuenta sin correo recibe el mismo mensaje de éxito y ningún correo: su contraseña la restablece
un administrador.

### Dónde queda la contraseña SMTP

- Viaja por stdin del exec de `kcadm`, nunca por argv, por el entorno de Keycloak ni por su Compose.
- **Keycloak la guarda en claro en su base** (`keycloak.realm_smtp_config`); es propio de su SMTP
  nativo y es una excepción aceptada. La API la devuelve enmascarada, pero un respaldo de la base o
  un `kc.sh export` del realm la contienen: deben tratarse como secretos, y restaurar uno antiguo
  puede devolver una credencial retirada (un converge la reemplaza).
- Deshabilitar borra las filas activas, pero no alcanza respaldos ni registros de PostgreSQL ya
  escritos. La revocación real es retirar la credencial en AWS.

### Fallas y reintento

- Si la escritura o la relectura del realm fallan, **el converge falla** y no informa éxito. Basta
  corregir la causa y repetirlo: no reemplaza el realm ni depende de lo que hizo el intento anterior.
- Al llegar a ese paso el `.env` ya se escribió, y el backend de ese run no se recreó: tras un converge
  fallido el backend, el `.env` y el realm pueden no coincidir hasta repetirlo.
- **Un deploy sin configuración del servidor no lo arregla ni lo detecta**: no recibe secretos, no toca
  Keycloak y su comprobación solo compara el `.env`. Puede terminar en verde con el realm atrasado,
  incluso con la credencial y la recuperación todavía activas tras un intento fallido de deshabilitar.
  La señal es el converge en rojo: se repite el converge, no el deploy.

### Diagnosticar una recuperación

Keycloak **muestra el mismo mensaje de éxito** si el envío falla, si no existe la cuenta o si no tiene
correo: el mensaje en pantalla no prueba nada. El realm tampoco guarda eventos de recuperación, solo de
login. La evidencia está en el log del contenedor, que nombra el error y no las credenciales:

```bash
sudo docker logs --since 15m keycloak 2>&1 | grep -E 'KC-SERVICES0029|EmailException'
```

Y la configuración aplicada, sin valores secretos (`user` y `password` no se muestran):

```bash
sudo docker exec postgres psql -U postgres -d piedrazul_db -c "select name, case when name in ('user','password') then '(oculto)' else value end as value from keycloak.realm_smtp_config order by name"
```

| Observación | Causa probable | Acción |
|---|---|---|
| `KC-SERVICES0029` con error de autenticación | credenciales incorrectas o retiradas | corregir los secretos y correr converge |
| `KC-SERVICES0029` con error de conexión | endpoint o salida de red | revisar `SES_SMTP_HOST` y la conectividad al puerto 587 |
| rechazo de destinatario | SES en sandbox o identidad sin verificar | requisitos externos de la tabla inicial |
| sin errores y sin correo | cuenta sin correo o inexistente, o SES aceptó y no entregó | revisar la cuenta y el remitente; ver SES en AWS |
| sin enlace en el login | SES deshabilitado o converge no aplicado | correr con `run_host_config=true` |

## Habilitar

Requisitos: lo pendiente de la tabla inicial y un backend desplegado que traiga el proveedor SES.
Habilitar también enciende la recuperación de contraseña: leer
[riesgo de la dirección de correo](#riesgo-de-la-dirección-de-correo).

1. Definir en `production-hetzner` el secreto `SES_SMTP_USERNAME`, el secreto `SES_SMTP_PASSWORD` y
   la variable `SES_SMTP_HOST`.
2. Definir `NOTIFICATIONS_SES_ENABLED=true` en el mismo environment.
3. Correr **Producción — Hetzner** con `run_host_config=true`. Sin deploy de aplicación el backend
   vigente se recrea con la configuración nueva; con `run_app_deploy=true`, lo hace el deploy. El
   mismo converge configura Keycloak.
4. Hacer una recuperación real con una cuenta de prueba y confirmar el correo en destino. Si no llega,
   [diagnosticar](#diagnosticar-una-recuperación) o deshabilitar SES.

Un deploy sin configuración del servidor **falla sin tocar nada** mientras el host no refleje la
decisión. Eso incluye un push procesado después de cambiar la variable.

## Cambiar el endpoint

Cambiar `SES_SMTP_HOST` en el environment y correr con `run_host_config=true`. El remitente se cambia
en `group_vars` y llega igual, al backend y a Keycloak.

## Rotar las credenciales

1. Cambiar los secretos en el environment.
2. Correr con `run_host_config=true`. El converge escribe `.env`, comprueba la entrega exacta,
   reescribe el SMTP de Keycloak y recrea el backend, y el run falla si el contenedor no quedó con
   las credenciales nuevas. Keycloak no se reinicia.
3. Comprobar un envío con las credenciales nuevas: el verde no demuestra que SES las acepte.
4. Retirar las credenciales anteriores en AWS.

**Un deploy sin configuración del servidor no rota nada**: no recibe credenciales y no puede
comprobar que las del host coincidan con las de GitHub. Termina bien, avisa con una anotación y su
resumen dice que no se renovaron ni compararon.

## Deshabilitar

1. Poner `NOTIFICATIONS_SES_ENABLED=false` o eliminar la variable.
2. Correr con `run_host_config=true`. El `.env` queda sin valores y el backend se recrea sin ellos; el
   run falla si Compose o el contenedor conservan algo. Keycloak queda sin SMTP y sin recuperación: el
   enlace desaparece del login.
3. Si el retiro es definitivo, eliminar los secretos y `SES_SMTP_HOST` del environment.

## Cómo leer el resultado

| Señal | Qué establece | Qué no establece |
|---|---|---|
| Preflight en verde | forma de la decisión, el endpoint y (con converge) las credenciales; sin copias externas | que las credenciales sean válidas o que la región tenga SES |
| Puerta de imagen en verde | la imagen seleccionada contiene `SesEmailProvider.class` | que el correo funcione |
| Converge en verde | Compose entrega exactamente lo validado, el backend tiene esa configuración y el realm de Keycloak muestra los valores no secretos y la recuperación esperados | conexión con SES, ni que SES acepte la credencial que Keycloak guardó |
| Deploy en verde | decisión y endpoint del host iguales a los del environment; credenciales presentes | que las credenciales sean las de GitHub, ni nada sobre Keycloak |
| Backend `healthy` | nada sobre el correo: el health no depende de SES | — |
| `audit-github-trust.sh` en `PASS` | alcance y presencia en GitHub | el `.env` del host, el backend o la entrega |

Solo un envío real, observado en destino, demuestra la entrega; ningún paso de la cadena envía uno.

## Problemas frecuentes

| Mensaje | Causa | Acción |
|---|---|---|
| `existe en el repositorio o la organización` | copia fuera del environment, o `classify` no pudo comprobarlo | eliminarla, cualquiera que sea su valor |
| `debe ser exactamente true o false` | valor distinto, con mayúsculas o espacios | corregir la variable |
| `no es un endpoint SMTP de SES` | otro servidor, puerto incluido o error de escritura | usar solo el nombre del endpoint |
| `falta el secreto …` | secreto ausente con SES habilitado | definirlo en `production-hetzner`, o deshabilitar SES |
| `SES está habilitado en el environment y no en el host` y similares | el environment cambió sin converge | correr con `run_host_config=true` |
| `no se pudo establecer que la imagen…` | imagen anterior al proveedor SES | seleccionar otra imagen o deshabilitar SES; este run no escribió `.env` |
| `no refleja la configuración de SES` | el backend no se recreó | correr un deploy; si el contenedor corre otra imagen, el converge no lo recrea |
| `KEYCLOAK SMTP: … no es de SES` | el realm tiene otro SMTP | decidir sobre él en la consola y repetir el converge |
| `KEYCLOAK SMTP: el realm no aceptó la configuración` o `No se informa como éxito` | la escritura o la relectura fallaron | corregir la causa nombrada y repetir el converge |
| `roles del service account de automatización divergentes` | realm anterior a `manage-realm` | correr una vez la recuperación autorizada |
| El backend no arranca con SES habilitado | el backend valida su configuración y rechaza lo que falte; el log nombra la propiedad, no el valor | corregir la entrada y volver a correr el converge |

Un converge que falla en la validación no escribe `.env`: la configuración anterior sigue en el host.
Tras corregir la entrada, basta repetirlo. Tampoco toca el realm de Keycloak.

## Secretos en el host

`/opt/piedrazul/.env` es `root:root 0600`; para inspeccionarlo a mano hace falta `sudo`. Esto limita
quién lee el archivo, pero no quién tiene acceso equivalente a root: el usuario `ansible` tiene
`sudo`, y quien acceda a Docker puede leer el entorno de los contenedores con `docker inspect`. Para
comprobar a mano, contar líneas sin mostrar valores:

```bash
sudo grep -c '^SES_SMTP_PASSWORD=.' /opt/piedrazul/.env
```

La misma contraseña también queda en la base de Keycloak: ver
[dónde queda la contraseña SMTP](#dónde-queda-la-contraseña-smtp).

## Límite de rollback

**No hacer rollback a imágenes del backend anteriores al proveedor SES**: pueden contener el
comportamiento antiguo de consola y de códigos de verificación, que no es una vía de entrega
aceptable. Esta regla es **operativa**: con SES habilitado la puerta de imagen rechaza esas imágenes,
pero con SES deshabilitado **nada lo impide técnicamente**. Quien pida un `image_tag` debe comprobarlo.
