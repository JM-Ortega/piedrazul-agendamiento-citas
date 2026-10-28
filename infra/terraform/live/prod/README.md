# live/prod — Entorno de producción de Piedrazul

Este es el root module de Terraform para el entorno de producción de Piedrazul.
Orquesta toda la infraestructura del proyecto componiendo los módulos disponibles
en `infra/terraform/modules/`.

## Qué despliega

- **Firewall** — Reglas de entrada y salida en Hetzner Cloud
- **Servidor** — VPS Ubuntu 24.04 en Hetzner Falkenstein (fsn1), cx33
- **DNS** — Registros A y CNAME en Cloudflare para los tres subdominios
- **Pages** — Proyecto de Cloudflare Pages del frontend Angular, de subida directa (sin integración Git ni previews automáticas), con dominio propio
- **Ajustes de zona** — Configuración TLS/HTTPS de la zona de Cloudflare
- **Origen protegido** — Authenticated Origin Pulls (`tls_client_auth`) de la zona

Este documento describe lo que el repositorio declara. El estado real de Hetzner,
Cloudflare, HCP Terraform y del servidor desplegado no se puede verificar desde el
repositorio.

## Arquitectura

Con `base_domain = "piedrazul.org"` (declarado en `piedrazul.auto.tfvars`):

```
Internet
  │
  ├── piedrazul.org      → Cloudflare Pages (Angular)
  ├── api.piedrazul.org  → Cloudflare (proxy) → VPS (Spring Boot via Traefik)
  └── auth.piedrazul.org → Cloudflare (proxy) → VPS (Keycloak via Traefik)
                                                    │
                                              Hetzner VPS (fsn1, cx33)
                                              Ubuntu 24.04
                                              Docker Compose
                                              Traefik + Spring Boot + Keycloak + PostgreSQL
```

El stack del servidor se define en `infra/compose/prod.yml` (que incluye `db.yml`,
`keycloak.yml`, `backend.yml` y `traefik.yml`) y lo despliega Ansible
(ver [`infra/ansible/ANSIBLE.md`](../../../ansible/ANSIBLE.md)).

## Postura de red declarada

**Tráfico web (80/443)**

- `module "firewall"` limita `web_sources` a `local.cloudflare_proxy_ips`
  (rangos de Cloudflare definidos en `cloudflare_ips.tf`).
- `module "dns"` declara `proxied_backend = true`, por lo que los registros `api`
  y `auth` pasan por el proxy de Cloudflare.
- `module "zone_settings"` declara SSL `strict`, TLS mínimo 1.2, TLS 1.3 y
  `always_use_https`, entre otros ajustes.
- `module "origin_security"` declara `tls_client_auth = on` (Authenticated Origin
  Pulls). En el origen, las rutas de Traefik (file provider, `routes.yml` generado por
  Ansible) usan la opción TLS `cloudflare-aop` (`infra/traefik/dynamic/tls.yml`), que exige
  certificado de cliente firmado por la CA de Cloudflare
  (`infra/traefik/certs/`). Los certificados del servidor los emite Let's Encrypt
  mediante desafío DNS de Cloudflare.

**SSH (22)**

- SSH no está protegido por Cloudflare. `module "firewall"` declara
  `ssh_sources = ["0.0.0.0/0", "::/0"]`, es decir, abierto a cualquier origen.
- El acceso se endurece en el propio servidor: cloud-init deshabilita la
  autenticación por contraseña y el login de root, y configura Fail2Ban para
  `sshd` (`modules/shared/templates/cloud-init.tftpl`); el rol Ansible `hardening`
  verifica que Fail2Ban esté activo. Solo se aceptan llaves SSH.

**ICMP**

- El módulo de firewall habilita ICMP entrante por defecto (`enable_icmp = true`)
  y este root module no lo modifica.

## Estructura de archivos

```
live/prod/
├── versions.tf            — Versiones de Terraform y providers + bloque cloud HCP
├── providers.tf           — Configuración de providers hcloud y cloudflare
├── variables.tf           — Declaración de variables de entrada
├── piedrazul.auto.tfvars  — Valores no sensibles de las variables
├── locals.tf              — Valores derivados (nombres de recursos y dominios)
├── cloudflare_ips.tf      — Rangos IP del proxy de Cloudflare (`cloudflare_proxy_ips`)
├── main.tf                — Composición de módulos
├── outputs.tf             — Outputs expuestos hacia HCP Terraform y workflows
└── README.md              — Este archivo
```

## Variables requeridas en HCP Terraform

El bloque `cloud` de `versions.tf` apunta al workspace `piedrazul-hetzner` de la
organización `Piedrazul` en HCP Terraform. Las variables con valor en
`piedrazul.auto.tfvars` (`project`, `base_domain`, `cloudflare_account_id`,
`cloudflare_zone_id`, `server_type`, `location`, `image`) están declaradas en el
repositorio; las demás deben existir en el
workspace, cuyo contenido no es verificable desde el repositorio.

| Variable                 | Tipo      | Sensitive | Descripción                               |
| ------------------------ | --------- | --------- | ----------------------------------------- |
| `project`                | terraform | No        | Nombre del proyecto — prefijo de recursos |
| `base_domain`            | terraform | No        | Dominio base — `piedrazul.org` en tfvars  |
| `cloudflare_account_id`  | terraform | No        | Account ID de Cloudflare                  |
| `cloudflare_zone_id`     | terraform | No        | Zone ID de la zona del dominio base       |
| `ansible_ssh_public_key` | terraform | No        | Llave pública SSH de Ansible              |
| `ops_ssh_public_key`     | terraform | No        | Llave pública SSH del usuario ops         |
| `server_type`            | terraform | No        | Tipo de servidor — default `cx33`         |
| `location`               | terraform | No        | Región Hetzner — default `fsn1`           |
| `image`                  | terraform | No        | Imagen OS — default `ubuntu-24.04`        |
| `HCLOUD_TOKEN`           | env       | Sí        | Token de API de Hetzner Cloud             |
| `CLOUDFLARE_API_TOKEN`   | env       | Sí        | Token de API de Cloudflare                |

La cuenta y la zona son las dedicadas de Piedrazul (`cloudflare_account_id`,
`cloudflare_zone_id` en `piedrazul.auto.tfvars`). `github_owner` y `github_repo` ya no
existen: el proyecto de Pages no se conecta a GitHub. Si el workspace todavía define esas
variables, Terraform solo advierte que no están declaradas; conviene quitarlas.

## Outputs

| Output          | Descripción                          |
| --------------- | ------------------------------------ |
| `server_ip`     | IP pública del servidor              |
| `server_id`     | ID del servidor en Hetzner           |
| `ssh_user`      | Usuario SSH para Ansible — `ansible` |
| `frontend_fqdn` | FQDN del frontend (`<base_domain>`)  |
| `api_fqdn`      | `api.<base_domain>`                  |
| `auth_fqdn`     | `auth.<base_domain>`                 |
| `pages_url`     | URL del proyecto en Cloudflare Pages |

## Cómo se ejecuta

Este workspace usa HCP Terraform en modo API-driven con ejecución remota. No se
ejecuta localmente en producción. La cadena de producción
(`.github/workflows/production-hetzner.yml`) sube la configuración como versión
provisional, crea un saved plan con `auto-apply=false` y publica una revisión saneada del
plan en el resumen del run de GitHub; con una segunda aprobación del environment
`production-hetzner`, confirma **ese** saved plan por la API (una sola solicitud) y sigue el
Apply hasta su resultado. La UI de HCP queda para diagnóstico excepcional. Requisitos del
workspace, que la cadena verifica antes de crear el run y otra vez antes de confirmar:
ejecución `remote` (o `agent`), *Apply method* manual (`auto-apply` y
`auto-apply-run-trigger` deshabilitados) y un `TF_API_TOKEN` que pueda leer el estado,
encolar y confirmar runs y leer el JSON del plan. Un workspace sin estado es una instalación nueva válida (ver
[`instalacion-nueva.md`](../../../../docs/operations/runbooks/instalacion-nueva.md)).

Para un plan local de validación (sin apply):

```bash
export TF_TOKEN_app_terraform_io="tu_token_hcp"
cd infra/terraform/live/prod
terraform init
terraform plan
```

## Módulos utilizados

| Módulo            | Ruta                             | Descripción                                               |
| ----------------- | -------------------------------- | --------------------------------------------------------- |
| `firewall`        | `modules/hetzner/firewall`       | Firewall Hetzner (SSH, HTTP/HTTPS, ICMP y salida)         |
| `server`          | `modules/hetzner/server`         | VPS Hetzner + cloud-init hardening                        |
| `pages`           | `modules/cloudflare/pages`       | Proyecto Cloudflare Pages de subida directa + dominio propio |
| `dns`             | `modules/cloudflare/dns`         | Registros DNS de frontend, API y autenticación            |
| `zone_settings`   | `modules/cloudflare/zone_settings` | Ajustes TLS/HTTPS de la zona de Cloudflare              |
| `origin_security` | `modules/cloudflare/origin_security` | Authenticated Origin Pulls de la zona                 |

## Decisiones de diseño importantes

**Falkenstein (fsn1) sobre Helsinki (hel1)** — Tests de latencia desde Colombia
mostraron 185ms promedio en fsn1 vs 201ms en hel1. Para un backend Spring Boot
que sirve principalmente JSON, la latencia por request importa más que el throughput.

**cx33 (4 vCPU / 8GB)** — Dimensionado para el stack completo corriendo en Docker Compose:
Spring Boot, PostgreSQL, Keycloak y Traefik simultáneamente.

**IPv6 deshabilitado** — El frontend vive en Cloudflare Pages que maneja IPv6
en su edge. El servidor no necesita IPv6 porque Cloudflare actúa como intermediario.

**Un solo usuario `ansible` para CI/CD, `ops` para emergencias** — Trazabilidad
en logs — los accesos de Ansible y los accesos manuales de emergencia aparecen
con usuarios distintos en `auth.log`.

**Pages de subida directa** — El proyecto no tiene `source` ni `build_config`: Cloudflare
no construye nada, no hay previews automáticas y la única autoridad de publicación es la
cadena de producción, que sube con wrangler el artefacto ya validado. Proyecto, cuenta y
dominio se leen de `piedrazul.auto.tfvars` (`infra/tests/pages-release-check.sh` falla si
cambia esa correspondencia o si el módulo vuelve a declarar un origen Git). El tipo del
proyecto se fija al crearlo: Cloudflare no convierte un proyecto de subida directa en uno
con Git ni al revés, y la cadena rechaza cualquier proyecto con `source`. Contrato:
[`docs/architecture/overview.md`](../../../../docs/architecture/overview.md#publicación-del-frontend-cloudflare-pages).

**Dominio propio: un dueño por recurso y orden explícito** — El módulo `dns` es el único
dueño del CNAME `piedrazul.org → <proyecto>.pages.dev`; el módulo `pages` es dueño del
proyecto y de la asociación del dominio (`cloudflare_pages_domain`). La asociación recibe el
id del registro DNS (`custom_domain_dns_record_id`) y lo referencia en una precondición:
esa referencia ordena el grafo proyecto → CNAME → asociación sin ciclos, así la API de Pages
encuentra el registro ya creado y Terraform no compite con un registro que la asociación
pudiera crear por su cuenta. Si la API de Pages modificara ese registro al asociar el
dominio, el siguiente plan lo mostraría como diferencia: un plan limpio después de la
instalación es parte de la verificación contra el entorno.
