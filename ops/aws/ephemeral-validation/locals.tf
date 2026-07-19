locals {
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 2)
  artifact_prefix    = "${var.bootstrap_object_prefix}/${var.run_id}"
  expires_at         = "${var.cleanup_start_at}Z"
  resource_prefix    = substr("gaja-${var.run_id}", 0, 20)
  ecs_ami_root_volume_size_gib = tonumber(one([
    for mapping in data.aws_ami.ecs_optimized_selected.block_device_mappings :
    mapping.ebs["volume_size"] if mapping.device_name == data.aws_ami.ecs_optimized_selected.root_device_name
  ]))

  common_tags = merge(var.tags, {
    Project   = "GAJA"
    ManagedBy = "terraform"
    Purpose   = "ephemeral-validation"
    RunId     = var.run_id
    ExpiresAt = local.expires_at
  })

  singleton_roles = toset([
    "db",
    "redis",
    "graphhopper",
    "load",
    "observability",
  ])

  singleton_private_ips = {
    db            = "10.88.10.10"
    redis         = "10.88.10.11"
    graphhopper   = "10.88.10.12"
    load          = "10.88.10.13"
    observability = "10.88.10.14"
  }

  app_nodes = {
    for index in range(var.app_count) : "app-${index + 1}" => {
      availability_zone_index = index
      private_ip              = "10.88.${10 + index}.20"
      role                    = "app"
    }
  }

  interface_endpoint_services = toset([
    "ec2messages",
    "ssm",
    "ssmmessages",
  ])
}
