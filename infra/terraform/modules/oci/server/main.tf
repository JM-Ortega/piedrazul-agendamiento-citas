data "oci_core_images" "ubuntu" {
  compartment_id           = var.compartment_id
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = var.shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_instance" "main" {
  compartment_id      = var.compartment_id
  availability_domain = var.availability_domain
  display_name        = "${var.project}-server"
  shape               = var.shape

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_in_gbs
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ubuntu.images[0].id
  }

  create_vnic_details {
    subnet_id        = var.subnet_id
    assign_public_ip = true
    hostname_label   = local.hostname_label
  }

  metadata = {
    ssh_authorized_keys = join("\n", var.ssh_public_keys)
    # OCI requiere user_data en base64 — el módulo maneja la codificación
    user_data = var.user_data != null ? base64encode(var.user_data) : null
  }

  lifecycle {
    # Evitar recreación cuando Oracle publica nuevas imágenes de Ubuntu 24.04
    # NOTA: ignore_changes en source_details oculta también cambios en boot_volume_size
    # Si en el futuro se necesita cambiar el tamaño del boot volume, quitar este ignore
    ignore_changes = [source_details]
  }
}
