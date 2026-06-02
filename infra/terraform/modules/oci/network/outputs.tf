output "vcn_id" {
  description = "ID de la VCN"
  value       = oci_core_vcn.main.id
}

output "subnet_id" {
  description = "ID de la subnet pública"
  value       = oci_core_subnet.main.id
}

output "route_table_id" {
  description = "ID de la route table"
  value       = oci_core_route_table.main.id
}

output "internet_gateway_id" {
  description = "ID del internet gateway"
  value       = oci_core_internet_gateway.main.id
}

output "vcn_cidr" {
  description = "CIDR block de la VCN"
  value       = oci_core_vcn.main.cidr_blocks[0]
}

output "subnet_cidr" {
  description = "CIDR block de la subnet pública"
  value       = oci_core_subnet.main.cidr_block
}

output "default_security_list_id" {
  description = "ID de la security list por defecto de la VCN"
  value       = oci_core_vcn.main.default_security_list_id
}
