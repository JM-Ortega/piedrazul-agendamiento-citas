variable "project" {
  description = "Nombre del proyecto — prefijo para todos los recursos"
  type        = string
}

variable "base_domain" {
  description = "Dominio base para los registros DNS"
  type        = string
}

variable "cloudflare_account_id" {
  description = "ID de la cuenta de Cloudflare"
  type        = string
}

variable "cloudflare_zone_id" {
  description = "ID de la zona en Cloudflare"
  type        = string
}

variable "github_owner" {
  description = "Propietario del repositorio en GitHub (usuario u organización)"
  type        = string
}

variable "github_repo" {
  description = "Nombre del repositorio en GitHub"
  type        = string
}

variable "ansible_ssh_public_key" {
  description = "Llave pública SSH para Ansible — automatización CI/CD"
  type        = string
}

variable "ops_ssh_public_key" {
  description = "Llave pública SSH del usuario ops — acceso de emergencia"
  type        = string
}
variable "server_type" {
  description = "Tipo de servidor en Hetzner (ej. cx33)"
  type        = string
  default     = "cx33"
}

variable "location" {
  description = "Ubicación del datacenter en Hetzner (ej. fsn1, nbg1)"
  type        = string
  default     = "fsn1"
}

variable "image" {
  description = "Imagen del sistema operativo (ej. ubuntu-24.04)"
  type        = string
  default     = "ubuntu-24.04"
}
