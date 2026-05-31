variable "compartment_id" {
  description = "OCID del compartment donde se creará la red"
  type        = string
}

variable "project" {
  description = "Nombre del proyecto — usado para nombrar los recursos de red"
  type        = string
}

variable "vcn_cidr" {
  description = "Bloque CIDR de la VCN"
  type        = string
  default     = "10.0.0.0/16"
}

variable "subnet_cidr" {
  description = "Bloque CIDR de la subnet pública"
  type        = string
  default     = "10.0.0.0/24"
}

variable "dns_label" {
  description = "DNS label para la VCN — debe iniciar con letra y ser alfanumérico"
  type        = string
  default     = null

  validation {
    condition     = var.dns_label == null || can(regex("^[a-zA-Z][a-zA-Z0-9]*$", var.dns_label))
    error_message = "dns_label debe iniciar con letra y contener solo caracteres alfanuméricos."
  }
}
