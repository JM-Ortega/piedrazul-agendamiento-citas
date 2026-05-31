variable "compartment_id" {
  description = "OCID del compartment donde se creará la instancia"
  type        = string
}

variable "project" {
  description = "Nombre del proyecto - usado para nombrar los recursos"
  type        = string
}

variable "subnet_id" {
  description = "ID de la subnet pública donde se desplegará la instancia"
  type        = string
}

variable "availability_domain" {
  description = "Availability domain donde se creará la instancia (ej. ootp:SA-BOGOTA-1-AD-1)"
  type        = string
}

variable "shape" {
  description = "Shape de la instancia OCI"
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "ocpus" {
  description = "Número de OCPUs para la instancia ARM"
  type        = number
  default     = 4
}

variable "memory_in_gbs" {
  description = "Memoria RAM en GB para la instancia ARM"
  type        = number
  default     = 24
}

variable "ssh_public_keys" {
  description = "Llaves públicas SSH - ansible y ops combinadas en una lista"
  type        = list(string)
}

variable "user_data" {
  description = "Script cloud-init para bootstrap de la instancia"
  type        = string
  default     = null
}
