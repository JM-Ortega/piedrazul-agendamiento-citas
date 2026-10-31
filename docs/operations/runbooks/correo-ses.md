# Correo por Amazon SES

## Propósito

Configurar, cambiar y retirar el correo por el SMTP de Amazon SES en producción. La instalación
inicial no lo necesita: **sin ninguna configuración de SES** se completan Run P, Run I, la
configuración del servidor y los deploys ordinarios.

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
el contenedor `backend`. Ni `migrate` ni los demás servicios reciben estas variables.

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
  ([despacho de notificaciones](../../architecture/overview.md#despacho-de-notificaciones)).

## Habilitar

Requisitos: lo pendiente de la tabla inicial y un backend desplegado que traiga el proveedor SES.

1. Definir en `production-hetzner` el secreto `SES_SMTP_USERNAME`, el secreto `SES_SMTP_PASSWORD` y
   la variable `SES_SMTP_HOST`.
2. Definir `NOTIFICATIONS_SES_ENABLED=true` en el mismo environment.
3. Correr **Producción — Hetzner** con `run_host_config=true`. Sin deploy de aplicación el backend
   vigente se recrea con la configuración nueva; con `run_app_deploy=true`, lo hace el deploy.

Un deploy sin configuración del servidor **falla sin tocar nada** mientras el host no refleje la
decisión. Eso incluye un push procesado después de cambiar la variable.

## Cambiar el endpoint

Cambiar `SES_SMTP_HOST` en el environment y correr con `run_host_config=true`.

## Rotar las credenciales

1. Cambiar los secretos en el environment.
2. Correr con `run_host_config=true`. El converge escribe `.env`, comprueba la entrega exacta y
   recrea el backend, y el run falla si el contenedor no quedó con las credenciales nuevas.
3. Retirar las credenciales anteriores en AWS.

**Un deploy sin configuración del servidor no rota nada**: no recibe credenciales y no puede
comprobar que las del host coincidan con las de GitHub. Termina bien, avisa con una anotación y su
resumen dice que no se renovaron ni compararon.

## Deshabilitar

1. Poner `NOTIFICATIONS_SES_ENABLED=false` o eliminar la variable.
2. Correr con `run_host_config=true`. El `.env` queda sin valores y el backend se recrea sin ellos; el
   run falla si Compose o el contenedor conservan algo.
3. Si el retiro es definitivo, eliminar los secretos y `SES_SMTP_HOST` del environment.

## Cómo leer el resultado

| Señal | Qué establece | Qué no establece |
|---|---|---|
| Preflight en verde | forma de la decisión, el endpoint y (con converge) las credenciales; sin copias externas | que las credenciales sean válidas o que la región tenga SES |
| Puerta de imagen en verde | la imagen seleccionada contiene `SesEmailProvider.class` | que el correo funcione |
| Converge en verde | Compose entrega exactamente lo validado y el backend tiene esa configuración | conexión con SES |
| Deploy en verde | decisión y endpoint del host iguales a los del environment; credenciales presentes | que las credenciales sean las de GitHub |
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
| El backend no arranca con SES habilitado | el backend valida su configuración y rechaza lo que falte; el log nombra la propiedad, no el valor | corregir la entrada y volver a correr el converge |

Un converge que falla en la validación no escribe `.env`: la configuración anterior sigue en el host.
Tras corregir la entrada, basta repetirlo.

## Secretos en el host

`/opt/piedrazul/.env` es `root:root 0600`; para inspeccionarlo a mano hace falta `sudo`. Esto limita
quién lee el archivo, pero no quién tiene acceso equivalente a root: el usuario `ansible` tiene
`sudo`, y quien acceda a Docker puede leer el entorno de los contenedores con `docker inspect`. Para
comprobar a mano, contar líneas sin mostrar valores:

```bash
sudo grep -c '^SES_SMTP_PASSWORD=.' /opt/piedrazul/.env
```

## Límite de rollback

**No hacer rollback a imágenes del backend anteriores al proveedor SES**: pueden contener el
comportamiento antiguo de consola y de códigos de verificación, que no es una vía de entrega
aceptable. Esta regla es **operativa**: con SES habilitado la puerta de imagen rechaza esas imágenes,
pero con SES deshabilitado **nada lo impide técnicamente**. Quien pida un `image_tag` debe comprobarlo.
