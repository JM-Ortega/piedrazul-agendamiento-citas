output "security_list_id" {
  description = "ID de la security list de la VCN"
  value       = oci_core_default_security_list.main.id
}
