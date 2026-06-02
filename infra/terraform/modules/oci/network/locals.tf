locals {
  dns_label = lower(
    var.dns_label != null
    ? var.dns_label
    : replace(var.project, "-", "")
  )
}
