locals {
  # hostname_label debe ser alfanumérico - OCI lo usa para DNS interno
  hostname_label = lower(replace(var.project, "-", ""))
}
