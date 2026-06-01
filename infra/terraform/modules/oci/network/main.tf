# Red virtual principal
resource "oci_core_vcn" "main" {
  compartment_id = var.compartment_id
  cidr_blocks    = [var.vcn_cidr]
  display_name   = "${var.project}-vcn"
  dns_label      = local.dns_label
}

# Puerta de salida a internet
resource "oci_core_internet_gateway" "main" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.project}-igw"
  enabled        = true
}

# Tabla de rutas - todo el tráfico saliente va por el internet gateway
resource "oci_core_route_table" "main" {
  compartment_id = var.compartment_id
  vcn_id         = oci_core_vcn.main.id
  display_name   = "${var.project}-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    network_entity_id = oci_core_internet_gateway.main.id
  }
}

# Subnet pública donde vivirá el servidor
resource "oci_core_subnet" "main" {
  compartment_id             = var.compartment_id
  vcn_id                     = oci_core_vcn.main.id
  cidr_block                 = var.subnet_cidr
  display_name               = "${var.project}-subnet"
  dns_label                  = "public"
  route_table_id             = oci_core_route_table.main.id
  prohibit_public_ip_on_vnic = false
  # security_list_ids omitido — usa la default de la VCN
}
