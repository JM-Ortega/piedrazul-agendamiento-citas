output "instance_id" {
  description = "OCID de la instancia OCI"
  value       = oci_core_instance.main.id
}

output "ipv4_address" {
  description = "IP pública efímera de la instancia"
  value       = oci_core_instance.main.public_ip
}

output "private_ip" {
  description = "IP privada de la instancia dentro de la VCN"
  value       = oci_core_instance.main.private_ip
}

output "ssh_user" {
  description = "Usuario SSH para automatización y despliegues"
  value       = "ansible"
}
