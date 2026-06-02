# live/oci — Entorno OCI de Piedrazul

Este es el root module de Terraform para el entorno de Oracle Cloud Infrastructure (OCI)
de Piedrazul. Orquesta toda la infraestructura del proyecto componiendo los módulos
disponibles en `infra/terraform/modules/`.

Existe en paralelo con `live/prod/` (Hetzner) con el objetivo de comparar latencia,
rendimiento y costos entre ambos proveedores de cómputo.

## Qué despliega

- **Red (VCN)** — Virtual Cloud Network con subnet pública, Internet Gateway y route table
- **Seguridad** — Default Security List de la VCN con reglas por fases
- **Servidor** — Instancia ARM Ubuntu 24.04 en OCI Bogotá (sa-bogota-1), VM.Standard.A1.Flex
- **DNS** — Registros A y CNAME en Cloudflare para los subdominios OCI
- **Pages** — Proyecto de Cloudflare Pages compartido con Hetzner (mismo frontend)

## Arquitectura

```
Internet
  │
  ├── piedrazul-oci.narvaezlab.dev      → Cloudflare Pages (Angular)
  ├── api.piedrazul-oci.narvaezlab.dev  → OCI ARM (Spring Boot via Traefik)
  └── auth.piedrazul-oci.narvaezlab.dev → OCI ARM (Keycloak via Traefik)
                                                │
                                        OCI VM.Standard.A1.Flex
                                        sa-bogota-1 / AD-1
                                        4 OCPU / 24GB RAM (Always Free)
                                        Ubuntu 24.04 ARM (aarch64)
                                        Docker Compose
                                        Spring Boot + Keycloak + PostgreSQL
```

## Diferencia con live/prod (Hetzner)

| Aspecto | Hetzner (live/prod) | OCI (live/oci) |
|---|---|---|
| Proveedor | Hetzner Cloud | Oracle Cloud Infrastructure |
| Región | Falkenstein, Alemania | Bogotá, Colombia |
| Shape | cx33 — 4 vCPU / 8GB x86 | VM.Standard.A1.Flex — 4 OCPU / 24GB ARM |
| Arquitectura | x86_64 | aarch64 (Ampere Altra) |
| Costo | ~€14/mes | Always Free |
| Red | Incluida en el servidor | VCN explícita (módulo network) |
| Firewall | hcloud_firewall | oci_core_default_security_list |
| Dominio frontend | piedrazul.narvaezlab.dev | piedrazul-oci.narvaezlab.dev |
| Dominio API | api.piedrazul.narvaezlab.dev | api.piedrazul-oci.narvaezlab.dev |
| Dominio Auth | auth.piedrazul.narvaezlab.dev | auth.piedrazul-oci.narvaezlab.dev |

## Decisiones de diseño importantes

**Security List sobre NSG** — Para una arquitectura de una sola instancia en una subnet
pública, la Security List es suficiente y más simple. Aplica las mismas reglas que el
`hcloud_firewall` de Hetzner. NSG queda como evolución futura si la red crece
(múltiples instancias, subnets privadas, bastion host).

**`oci_core_default_security_list` sobre `oci_core_security_list`** — La VCN crea una
security list por defecto automáticamente. Modificar esa lista evita la dependencia
circular entre los módulos `network` (que necesita el security_list_id para la subnet)
y `security` (que necesita el vcn_id para crear la lista). El módulo `network` expone
el `default_security_list_id` y `security` lo modifica directamente.

**Ubuntu 24.04 ARM** — OCI publica imágenes mensuales de Ubuntu 24.04 para aarch64
(`Canonical-Ubuntu-24.04-aarch64-*`). El módulo `server` usa un data source para
encontrar la imagen más reciente automáticamente sin hardcodear OCIDs que cambian
por región y versión.

**Dominios con sufijo `-oci`** — Para poder tener ambos entornos activos simultáneamente
durante la fase de comparación, los dominios de OCI llevan el sufijo `-oci`. Hetzner
mantiene los dominios principales sin sufijo por ser el entorno establecido.

**IP efímera** — Igual que Hetzner, la IP pública de la instancia es efímera. Terraform
gestiona el DNS en el mismo apply, manteniendo siempre los registros sincronizados
con la IP real del servidor.

**cloud-init reutilizado de Hetzner** — El template `cloud-init.tftpl` es idéntico al
de Hetzner — mismos usuarios `ansible` y `ops`, mismo fail2ban, mismo hardening SSH.
Ubuntu en ARM y x86 es compatible. Solo cambia cómo el provider consume el template:
OCI requiere base64 en `metadata.user_data`, Hetzner acepta texto plano en `user_data`.

**Always Free — 4 OCPU / 24GB** — El tier gratuito de OCI da 3.000 horas de OCPU y
18.000 horas de GB de RAM por mes, equivalente a una instancia VM.Standard.A1.Flex
con 4 OCPU y 24GB de forma permanente. No hay costo adicional mientras se usen solo
recursos del tier gratuito.

## Estado actual por fases

### Fase 1 — Activa
- SSH abierto a `0.0.0.0/0` — seguridad por llave ED25519
- HTTP/HTTPS abiertos a `0.0.0.0/0`
- `api` y `auth` con `proxied_backend = false` — sin Cloudflare proxy
- ICMP habilitado para diagnóstico

### Fase 2 — Pendiente (requiere Traefik + Let's Encrypt)
- Cambiar `web_sources` en `module "security"` a `local.cloudflare_ips`
- Cambiar `proxied_backend = false` a `true` en `module "dns"`
- Agregar `data.tf` con `data "cloudflare_ip_ranges" "main" {}`
- Descomentar `local.cloudflare_ips` en `locals.tf`

### Fase 3 — Pendiente (requiere Tailscale)
- Cambiar `enable_ssh = false` en `module "security"`
- Configurar GitHub Actions con Tailscale GitHub Action oficial
- Ansible conecta por IP/hostname de Tailscale en vez de SSH público

## Estructura de archivos

```
live/oci/
├── versions.tf   — Versiones de Terraform y providers + bloque cloud HCP
├── providers.tf  — Configuración de providers OCI y Cloudflare
├── variables.tf  — Variables recibidas desde HCP Terraform
├── locals.tf     — Valores derivados (dominios, AD, fases de seguridad)
├── main.tf       — Composición de módulos
├── outputs.tf    — Outputs expuestos hacia HCP Terraform y workflows
└── README.md     — Este archivo
```

## Variables requeridas en HCP Terraform

### Variable Set `piedrazul-shared` (compartido con piedrazul-hetzner)

| Variable | Tipo | Sensitive | Descripción |
|---|---|---|---|
| `project` | terraform | No | Nombre del proyecto — prefijo de recursos |
| `base_domain` | terraform | No | Dominio base — `narvaezlab.dev` |
| `cloudflare_account_id` | terraform | No | Account ID de Cloudflare |
| `cloudflare_zone_id` | terraform | No | Zone ID de narvaezlab.dev |
| `github_owner` | terraform | No | Usuario GitHub — `JM-Ortega` |
| `github_repo` | terraform | No | Repositorio GitHub |
| `ansible_ssh_public_key` | terraform | No | Llave pública SSH de Ansible |
| `ops_ssh_public_key` | terraform | No | Llave pública SSH del usuario ops |
| `CLOUDFLARE_API_TOKEN` | env | Sí | Token de API de Cloudflare |

### Workspace `piedrazul-oci` (específicas de OCI)

| Variable | Tipo | Sensitive | Descripción |
|---|---|---|---|
| `oci_tenancy_ocid` | terraform | Sí | OCID del tenancy de OCI |
| `oci_user_ocid` | terraform | Sí | OCID del usuario de OCI |
| `oci_fingerprint` | terraform | Sí | Fingerprint del API key pair |
| `oci_region` | terraform | No | Región — `sa-bogota-1` |
| `oci_private_key_base64` | terraform | Sí | Llave privada del API key en base64 |
| `oci_compartment_id` | terraform | Sí | OCID del compartment raíz |

## Outputs

| Output | Descripción |
|---|---|
| `server_ip` | IP pública efímera de la instancia OCI |
| `server_id` | OCID de la instancia OCI |
| `ssh_user` | Usuario SSH para Ansible — `ubuntu` |
| `frontend_fqdn` | `piedrazul-oci.narvaezlab.dev` |
| `api_fqdn` | `api.piedrazul-oci.narvaezlab.dev` |
| `auth_fqdn` | `auth.piedrazul-oci.narvaezlab.dev` |
| `pages_url` | URL del proyecto en Cloudflare Pages |

## Cómo se ejecuta

Este workspace usa HCP Terraform en modo API-driven. No se ejecuta localmente
en producción. Los cambios se aplican a través de GitHub Actions.

Para un plan local de validación (sin apply):

```bash
export TF_TOKEN_app_terraform_io="tu_token_hcp"
cd infra/terraform/live/oci
terraform init
terraform plan
```

## Módulos utilizados

| Módulo | Ruta | Descripción |
|---|---|---|
| `network` | `modules/oci/network` | VCN + subnet pública + internet gateway + route table |
| `security` | `modules/oci/security` | Default security list con reglas por fases |
| `server` | `modules/oci/server` | Instancia ARM + cloud-init hardening |
| `dns` | `modules/dns` | Registros DNS + zone settings Cloudflare |
| `pages` | `modules/pages` | Proyecto Cloudflare Pages con integración GitHub |

## Nota sobre el módulo security y dependencias

El módulo `security` modifica la `default_security_list` creada automáticamente
por OCI al crear la VCN, en vez de crear una nueva security list independiente.
Esto evita una dependencia circular: la subnet necesita el `security_list_id`
al momento de crearse, pero ese ID no existe hasta que se crea la security list,
que a su vez necesita el `vcn_id` que viene de la misma subnet.

Orden de dependencias en el apply:
```
1. module.network → crea VCN, expone default_security_list_id y subnet_id
2. module.security → modifica la default security list con las reglas de Piedrazul
3. module.server → crea la instancia en la subnet
4. module.dns → apunta los subdominios a la IP del servidor
5. module.pages → crea el proyecto de Cloudflare Pages
```
