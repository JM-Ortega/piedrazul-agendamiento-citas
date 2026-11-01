# ADR-007: Instalación por etapas y confianza SSH fijada

- Estado: Propuesto
- Fecha: 2026-10-30
- Impacto: Despliegue

## Contexto

La cadena `production-hetzner.yml` instalaba un servidor nuevo en un solo run: Terraform lo creaba y, sin pausa, SSH y Ansible se conectaban con la verificación de la clave del host desactivada. Los secretos del converge se entregaban a quien respondiera en `server_ip`. No existe un momento de ese run en el que un humano pueda autenticar la clave de un servidor que acaba de nacer, y una clave aprendida en la primera conexión (TOFU) no autentica nada.

Además, la base del rango de push era la conclusión del run: cualquier run push en `success` avanzaba el rango, aunque no hubiera procesado nada. Eso impedía registrar un push diferido sin perder sus cambios.

## Opciones consideradas

- **TOFU o `accept-new`.** Sin pasos manuales, pero la primera conexión queda sin autenticar, y es justo la que entrega los secretos.
- **Claves del host generadas por Terraform o cloud-init.** Automatiza el pin, pero la clave privada pasaría por el estado de HCP y por `user_data`.
- **PKI o certificados SSH, servicio de atestación.** Más fuerte y mucho más costoso: una CA o un servicio más que operar, para un servidor único.
- **Pin manual con instalación en dos runs.** Un paso humano solo al crear o reemplazar el servidor; ningún componente nuevo.
- **Fallar a propósito el primer push para detener la cadena.** Sin cambios en el rango, pero normaliza un fallo como procedimiento y entrena a ignorarlo.

## Decisión

**Dos etapas explícitas** por dispatch para crear o reemplazar el servidor: `stage=provision` (Run P) revisa el plan y aplica con las comprobaciones previas de una instalación completa, y termina antes de cualquier SSH. Solo un Apply que creó o reemplazó el servidor se informa como `PROVISIONED / TRUST PENDING / NOT INSTALLED`; un plan sin cambios se informa como `INFRASTRUCTURE UNCHANGED / TRUST AND INSTALLATION NOT EVALUATED`, y un Apply que no tocó el servidor como `INFRASTRUCTURE UPDATED / TRUST AND INSTALLATION NOT EVALUATED`. Un operador autentica la clave del host por la consola de Hetzner, un canal independiente de la conexión SSH, y registra el pin a mano. `stage=install` (Run I) exige un plan nuevo sin cambios, nunca aplica, verifica la identidad y sigue como cualquier deploy. Cada run conserva el modelo de aprobaciones existente. Un plan que crea o reemplaza el servidor falla fuera de Run P.

**Retiro:** un plan que destruye el servidor sin crear otro falla en todas las etapas. Retirar el servidor es una decisión operativa aparte, sin modo propio en esta cadena.

**Pin manual:** `PRODUCTION_SSH_HOST_PIN`, variable del environment `production-hetzner`, con la forma `<server_id> <server_ip> ssh-ed25519 SHA256:<huella>`. Se usa la huella porque el operador la transcribe desde una consola. Liga la clave a la instancia y a la dirección del state version exacto: una IP reutilizada no hereda confianza. La clave privada la genera OpenSSH en el servidor; nunca pasa por Terraform ni HCP.

**Verificación:** `host-trust.sh` valida el pin antes de tocar la red, lee la clave ofrecida sin credenciales y solo si coincide crea un `known_hosts` y un `ssh_config` de ese run, que usan el SSH directo y Ansible. La autenticación real es el handshake con `StrictHostKeyChecking=yes`. Ningún workflow descubre ni escribe el pin, y una identidad distinta nunca muestra la huella observada.

**Sin respaldo fuera del environment:** `vars` resuelve environment, repositorio y organización sin indicar el origen. El job `classify`, que no tiene environment, comprueba que la variable no exista fuera de él y la cadena falla cerrado si existe.

**Push diferido:** `vars.PRODUCTION_PUSH_DEPLOY`, deferred si no está definida. Un push diferido termina en `success` sin efectos y sin avanzar el rango. La base del rango pasa a ser una señal positiva: el job sin privilegios `range_processed`, que solo corre en un push ordinario verificado por `conclude`. Se reutiliza la API de jobs que `classify` ya puede leer: sin permisos nuevos, sin commit status pendiente y sin un registro aparte.

**Retención obligatoria para crear o reemplazar:** Run P solo aplica un plan que crea o reemplaza el servidor si `PRODUCTION_PUSH_DEPLOY` está sin definir o en `deferred`. Se lee en `classify`, que no tiene environment y por eso ve el mismo valor que un push, y viaja como salida: dentro del environment `vars` podría estar ensombrecida. La barrera corre antes de la segunda aprobación y se repite justo antes del Apply, cuando además se relee la variable por si se levantó durante la espera. Solo lee: el operador la cambia a mano. Una recuperación que no crea ni reemplaza el servidor no la exige, así que la retención no produce un bloqueo mutuo.

## Consecuencias

- Crear o reemplazar el servidor exige dos dispatches y una acción humana sin plazo. Las operaciones ordinarias siguen siendo un solo run.
- Rotar la clave o reinstalar el servidor detiene la cadena hasta autenticar la nueva identidad; es el comportamiento buscado.
- El primer push procesado tras una instalación, o tras un historial sin señal, clasifica el árbol completo. Es más trabajo, y seguro: nunca omite cambios.
- Run P construye y evalúa imágenes que Run I vuelve a evaluar; lo que vence no se hereda entre runs.
- Quitar un servidor del código de Terraform ya no se puede aplicar con esta cadena: hay que decidirlo y ejecutarlo aparte.
- La relectura antes del Apply puede quedar ensombrecida por una variable del environment; la lectura de `classify` sigue siendo la que decide. Levantar la retención después de esa relectura no se detecta, pero el Apply ya está en curso.
- La seguridad del pin depende de la protección del environment y de quien administra el repositorio. Este ADR no agrega otra capa.
- El reemplazo de un servidor con datos sigue fuera de esta decisión: la recuperación de datos clínicos es una dependencia aparte.
- `sshd-check` lee la configuración efectiva de `sshd -T`, no el proceso en ejecución.

## Referencias

- `docs/operations/runbooks/instalacion-nueva.md`
- `.github/scripts/production-stage.sh`, `.github/scripts/host-trust.sh`, `.github/scripts/production-range-base.sh`
- `infra/tests/production-stage-check.sh`, `infra/tests/ssh-host-trust-check.sh`
