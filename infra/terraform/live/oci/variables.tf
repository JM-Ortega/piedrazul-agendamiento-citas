# ── Compartidas (variable set piedrazul-shared) ──────────────────────────────

variable "project" {
  description = "Nombre del proyecto - prefijo para todos los recursos"
  type        = string
}

variable "base_domain" {
  description = "Dominio base para los registros DNS"
  type        = string
}

variable "cloudflare_account_id" {
  description = "Cloudflare Account ID"
  type        = string
}

variable "cloudflare_zone_id" {
  description = "Zone ID de narvaezlab.dev"
  type        = string
}

variable "github_owner" {
  description = "GitHub username"
  type        = string
}

variable "github_repo" {
  description = "Nombre del repositorio GitHub"
  type        = string
}

variable "ansible_ssh_public_key" {
  description = "Llave pública SSH para Ansible - automatización CI/CD"
  type        = string
}

variable "ops_ssh_public_key" {
  description = "Llave pública SSH personal - acceso de emergencia"
  type        = string
}

# ── Específicas de OCI (workspace piedrazul-oci) ──────────────────────────────

variable "oci_tenancy_ocid" {
  description = "OCID del tenancy de OCI"
  type        = string
  sensitive   = true
}

variable "oci_user_ocid" {
  description = "OCID del usuario de OCI para autenticación"
  type        = string
  sensitive   = true
}

variable "oci_fingerprint" {
  description = "Fingerprint del API key pair de OCI"
  type        = string
  sensitive   = true
}

variable "oci_region" {
  description = "Región de OCI donde se desplegará la infra"
  type        = string
}

variable "oci_private_key_base64" {
  description = "Llave privada del API key de OCI en base64"
  type        = string
  sensitive   = true
}

variable "oci_compartment_id" {
  description = "OCID del compartment donde se crearán los recursos"
  type        = string
  sensitive   = true
}
