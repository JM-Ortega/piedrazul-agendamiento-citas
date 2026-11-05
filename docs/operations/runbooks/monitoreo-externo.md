# Monitoreo externo (UptimeRobot)

## Propósito

Contrato para activar más adelante un monitor independiente del servidor: detecta lo que el stack
privado no puede avisar porque cae con él (servidor, red, Cloudflare, DNS, certificados) y mide la
disponibilidad tal como la ve un cliente de Internet.

**Estado.** Solo especificación. **No hay cuenta, monitores ni contactos de alerta configurados**
y el repositorio no contiene credenciales ni claves de API de UptimeRobot. Ninguna prueba local
depende de UptimeRobot. La activación es una unidad de trabajo externa aparte.

## Monitores

Los tres son peticiones HTTPS GET anónimas por la ruta pública (Cloudflare → Traefik con AOP →
servicio), sin cabeceras propias, sin seguir pasos de login y sin datos de pacientes.

| Monitor | URL | Éxito | Falla que detecta |
|---|---|---|---|
| API | `https://api.piedrazul.org/actuator/health` | HTTP 200 y la palabra clave `"status":"UP"` en el cuerpo | servidor, Traefik o backend caídos; backend sin base de datos (el health responde 503 `DOWN`); certificado o DNS |
| Keycloak (OIDC) | `https://auth.piedrazul.org/realms/piedrazul/.well-known/openid-configuration` | HTTP 200 y la palabra clave `"issuer":"https://auth.piedrazul.org/realms/piedrazul"` | Keycloak caído o sin el realm; hostname de Keycloak mal configurado (el issuer cambiaría); la ruta de login de los usuarios rota |
| Frontend | `https://piedrazul.org/` | HTTP 200 | Cloudflare Pages, dominio propio o DNS |

- **Palabra clave.** Un 200 de una página de error de Cloudflare o de un proxy no basta: los dos
  primeros exigen el contenido propio del servicio. Si el plan de la cuenta no admite monitores de
  palabra clave, usar HTTP con código 200 y anotar que un 200 inesperado no se detecta.
- **Frontend y Access.** El frontend puede estar temporalmente detrás de Cloudflare Access para
  pruebas de staging; entonces el monitor ve una redirección a `*.cloudflareaccess.com` (302) y
  lo marcaría caído. Mientras dure, pausar ese monitor o aceptarlo como caída esperada; no
  agregar una credencial de Access al monitor. API y Keycloak nunca están detrás de Access.
- **Grafana no se monitorea aquí**: está detrás de Access y su caída no afecta a los usuarios; la
  ve el operador.
- **Cloudflare.** Las reglas de seguridad de la zona (WAF, Bot Fight Mode, desafíos) no deben
  desafiar a UptimeRobot en estas tres rutas; si lo hacen, el monitor verá 403 o un desafío. Se
  comprueba en la activación, sin abrir excepciones más amplias que esas rutas.

## Frecuencia, confirmación y recuperación

- Intervalo: el mínimo del plan (en el plan gratuito, 5 min; verificar en la activación).
- Una falla se confirma con el reintento propio de UptimeRobot antes de notificar; una caída de
  un solo intento no avisa. Tiempo de detección esperado: entre uno y dos intervalos.
- Se notifica la caída y la **recuperación** (subida) de cada monitor.
- Tiempo máximo de respuesta: el valor por defecto del servicio; un health lento por encima de él
  cuenta como caído.

## Contactos de alerta

Correo del mantenedor como contacto base. Discord como contacto adicional solo si el plan lo
incluye y con un webhook **distinto** del de Alertmanager (rotar o revocar uno no rompe el otro);
ese webhook vive en la cuenta de UptimeRobot, nunca en el repositorio ni en el servidor.

## Lo que no cubre

- El stack de observabilidad (Prometheus, Alertmanager, Grafana): su silencio lo delata el
  latido diario de Alertmanager en Discord ([Alertas](alertas.md)), no UptimeRobot. Un monitor de
  latido (*heartbeat*) haría esa vigilancia automática, pero no es necesario para S3 y depende del
  plan; queda como mejora opcional.
- Las funciones de la aplicación más allá del health (agendamiento, login completo).
- Fallas de un solo usuario o de una región que UptimeRobot no ve.

## Activación (pendiente)

1. Crear los tres monitores con las URL y palabras clave de arriba y el contacto de correo.
2. Confirmar que los tres quedan *up* y que Cloudflare no los desafía.
3. Prueba de caída controlada en staging (detener el backend) y comprobar la notificación de caída
   y la de recuperación; no en producción con usuarios.
4. Registrar en este documento la fecha, el plan, el intervalo real y si se usan palabras clave.
