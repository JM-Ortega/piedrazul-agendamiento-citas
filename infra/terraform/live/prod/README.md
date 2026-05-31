# live/prod — Entorno de producción de Piedrazul

Este es el root module de Terraform para el entorno de producción de Piedrazul.
Orquesta toda la infraestructura del proyecto componiendo los módulos disponibles
en `infra/terraform/modules/`.

## Qué despliega

- **Firewall** — Reglas de entrada y salida en Hetzner Cloud
- **Servidor** — VPS Ubuntu 24.04 en Hetzner Falkenstein (fsn1), cx33
- **DNS** — Registros A y CNAME en Cloudflare para los tres subdominios
- **Pages** — Proyecto de Cloudflare Pages con integración GitHub para el frontend Angular

## Arquitectura

```
Internet
  │
  ├── piedrazul.narvaezlab.dev      → Cloudflare Pages (Angular)
  ├── api.piedrazul.narvaezlab.dev  → VPS (Spring Boot via Traefik)
  └── auth.piedrazul.narvaezlab.dev → VPS (Keycloak via Traefik)
                                           │
                                     Hetzner VPS (fsn1, cx33)
                                     Ubuntu 24.04
                                     Docker Compose
                                     Spring Boot + Keycloak + PostgreSQL
```

## Estado actual por fases

### Fase 1 — Activa

- SSH abierto a `0.0.0.0/0` — seguridad por llave ED25519
- HTTP/HTTPS abiertos a `0.0.0.0/0`
- `api` y `auth` con `proxied_backend = false` — sin Cloudflare proxy
- SSL zona configurado en `strict` y `always_use_https = on`

### Fase 2 — Pendiente (requiere Traefik + Let's Encrypt)

- Cambiar `web_sources` en `module "firewall"` a `local.cloudflare_ips`
- Cambiar `proxied_backend = false` a `true` en `module "dns"`
- Agregar `data.tf` con `data "cloudflare_ip_ranges" "main" {}`
- Agregar `local.cloudflare_ips` en `locals.tf`

### Fase 3 — Pendiente (requiere Tailscale)

- Cambiar `enable_ssh = false` en `module "firewall"`
- Configurar GitHub Actions con Tailscale GitHub Action oficial
- Ansible conecta por IP/hostname de Tailscale en vez de SSH público

## Estructura de archivos

```
live/prod/
├── versions.tf   — Versiones de Terraform y providers + bloque cloud HCP
├── providers.tf  — Configuración de providers hcloud y cloudflare
├── variables.tf  — Variables recibidas desde HCP Terraform
├── locals.tf     — Valores derivados (nombres de recursos y dominios)
├── main.tf       — Composición de módulos
├── outputs.tf    — Outputs expuestos hacia HCP Terraform y workflows
└── README.md     — Este archivo
```

## Variables requeridas en HCP Terraform

Todas las variables están configuradas en el workspace `piedrazul-infra`
de la organización `Piedrazul` en HCP Terraform.

| Variable                 | Tipo      | Sensitive | Descripción                               |
| ------------------------ | --------- | --------- | ----------------------------------------- |
| `project`                | terraform | No        | Nombre del proyecto — prefijo de recursos |
| `base_domain`            | terraform | No        | Dominio base — `narvaezlab.dev`           |
| `cloudflare_account_id`  | terraform | No        | Account ID de Cloudflare                  |
| `cloudflare_zone_id`     | terraform | No        | Zone ID de narvaezlab.dev                 |
| `github_owner`           | terraform | No        | Usuario GitHub — `JM-Ortega`              |
| `github_repo`            | terraform | No        | Repositorio GitHub                        |
| `ansible_ssh_public_key` | terraform | No        | Llave pública SSH de Ansible              |
| `ops_ssh_public_key`     | terraform | No        | Llave pública SSH del usuario ops         |
| `server_type`            | terraform | No        | Tipo de servidor — default `cx33`         |
| `location`               | terraform | No        | Región Hetzner — default `fsn1`           |
| `image`                  | terraform | No        | Imagen OS — default `ubuntu-24.04`        |
| `HCLOUD_TOKEN`           | env       | Sí        | Token de API de Hetzner Cloud             |
| `CLOUDFLARE_API_TOKEN`   | env       | Sí        | Token de API de Cloudflare                |

## Outputs

| Output          | Descripción                          |
| --------------- | ------------------------------------ |
| `server_ip`     | IP pública del servidor              |
| `server_id`     | ID del servidor en Hetzner           |
| `ssh_user`      | Usuario SSH para Ansible — `ansible` |
| `frontend_fqdn` | `piedrazul.narvaezlab.dev`           |
| `api_fqdn`      | `api.piedrazul.narvaezlab.dev`       |
| `auth_fqdn`     | `auth.piedrazul.narvaezlab.dev`      |
| `pages_url`     | URL del proyecto en Cloudflare Pages |

## Cómo se ejecuta

Este workspace usa HCP Terraform en modo API-driven. No se ejecuta localmente
en producción. Los cambios se aplican a través de GitHub Actions que envía
el plan a HCP Terraform.

Para un plan local de validación (sin apply):

```bash
export TF_TOKEN_app_terraform_io="tu_token_hcp"
cd infra/terraform/live/prod
terraform init
terraform plan
```

## Módulos utilizados

| Módulo     | Ruta               | Descripción                                      |
| ---------- | ------------------ | ------------------------------------------------ |
| `firewall` | `modules/firewall` | Firewall Hetzner con reglas por fases            |
| `server`   | `modules/server`   | VPS Hetzner + cloud-init hardening               |
| `dns`      | `modules/dns`      | Registros DNS + zone settings Cloudflare         |
| `pages`    | `modules/pages`    | Proyecto Cloudflare Pages con integración GitHub |

## Decisiones de diseño importantes

**Falkenstein (fsn1) sobre Helsinki (hel1)** — Tests de latencia desde Colombia
mostraron 185ms promedio en fsn1 vs 201ms en hel1. Para un backend Spring Boot
que sirve principalmente JSON, la latencia por request importa más que el throughput.

**cx33 (4 vCPU / 8GB)** — Dimensionado para el stack completo corriendo en Docker Compose:
Spring Boot, PostgreSQL, Redis, Keycloak, RabbitMQ y Traefik simultáneamente.

**IPv6 deshabilitado** — El frontend vive en Cloudflare Pages que maneja IPv6
en su edge. El servidor no necesita IPv6 porque Cloudflare actúa como intermediario.

**Un solo usuario `ansible` para CI/CD, `ops` para emergencias** — Trazabilidad
en logs — los accesos de Ansible y los accesos manuales de emergencia aparecen
con usuarios distintos en `auth.log`.

**`path_includes = ["frontend/**"]`en Pages** — Monorepo con backend y frontend.
Solo rebuilde el frontend cuando cambia algo en`frontend/`. Cambios en el backend
o en `infra/` no disparan builds innecesarios en Cloudflare Pages.
