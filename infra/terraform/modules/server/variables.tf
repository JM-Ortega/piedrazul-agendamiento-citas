variable "name" {
  description = "Nombre del servidor en Hetzner"
  type        = string
}

variable "server_type" {
  description = "Tipo de servidor en Hetzner (ej. cx33)"
  type        = string
}

variable "location" {
  description = "Ubicación del datacenter en Hetzner (ej. fsn1)"
  type        = string
}

variable "image" {
  description = "Imagen del sistema operativo (ej. ubuntu-24.04)"
  type        = string
}

variable "ssh_public_keys" {
  description = "Llaves públicas SSH para automatización"
  type        = list(string)
}

variable "ops_ssh_public_key" {
  description = "Llave pública SSH del usuario ops para acceso de emergencia"
  type        = string
}

variable "firewall_ids" {
  description = "IDs de firewalls asociados al servidor"
  type        = list(number)
  default     = []
}
