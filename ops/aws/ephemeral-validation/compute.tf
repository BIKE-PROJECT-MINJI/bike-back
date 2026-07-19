resource "aws_instance" "app" {
  for_each = local.app_nodes

  ami                                  = var.ecs_optimized_ami_id
  instance_type                        = var.instance_types["app"]
  subnet_id                            = aws_subnet.private[tostring(each.value.availability_zone_index)].id
  private_ip                           = each.value.private_ip
  vpc_security_group_ids               = [aws_security_group.node["app"].id]
  iam_instance_profile                 = aws_iam_instance_profile.node.name
  associate_public_ip_address          = false
  monitoring                           = false
  instance_initiated_shutdown_behavior = "terminate"

  credit_specification {
    cpu_credits = "standard"
  }

  user_data = templatefile("${path.module}/templates/bootstrap.tftpl", {
    artifact_bucket_name    = var.artifact_bucket_name
    bootstrap_object_key    = "${local.artifact_prefix}/app/bootstrap.sh"
    role                    = each.key
    run_id                  = var.run_id
    secret_parameter_prefix = var.secret_parameter_prefix
    shutdown_minutes        = var.ttl_minutes - 5
  })
  user_data_replace_on_change = true

  root_block_device {
    delete_on_termination = true
    encrypted             = true
    volume_size           = var.root_volume_sizes_gib["app"]
    volume_type           = "gp3"

    tags = {
      Name = "gaja-${var.run_id}-${each.key}-root"
      Role = "app"
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
    http_tokens                 = "required"
    instance_metadata_tags      = "disabled"
  }

  lifecycle {
    precondition {
      condition     = var.root_volume_sizes_gib["app"] >= local.ecs_ami_root_volume_size_gib
      error_message = "App root volume is smaller than the pinned AMI root snapshot. Run preflight again."
    }
  }

  tags = {
    Name = "gaja-${var.run_id}-${each.key}"
    Role = "app"
  }

  depends_on = [
    terraform_data.bootstrap_prerequisites,
    aws_vpc_endpoint.interface,
    aws_vpc_endpoint.s3,
  ]
}

resource "aws_instance" "singleton" {
  for_each = local.singleton_roles

  ami                                  = var.ecs_optimized_ami_id
  instance_type                        = var.instance_types[each.value]
  subnet_id                            = aws_subnet.private["0"].id
  private_ip                           = local.singleton_private_ips[each.value]
  vpc_security_group_ids               = [aws_security_group.node[each.value].id]
  iam_instance_profile                 = aws_iam_instance_profile.node.name
  associate_public_ip_address          = false
  monitoring                           = false
  instance_initiated_shutdown_behavior = "terminate"

  credit_specification {
    cpu_credits = "standard"
  }

  user_data = templatefile("${path.module}/templates/bootstrap.tftpl", {
    artifact_bucket_name    = var.artifact_bucket_name
    bootstrap_object_key    = "${local.artifact_prefix}/${each.value}/bootstrap.sh"
    role                    = each.value
    run_id                  = var.run_id
    secret_parameter_prefix = var.secret_parameter_prefix
    shutdown_minutes        = var.ttl_minutes - 5
  })
  user_data_replace_on_change = true

  root_block_device {
    delete_on_termination = true
    encrypted             = true
    volume_size           = var.root_volume_sizes_gib[each.value]
    volume_type           = "gp3"

    tags = {
      Name = "gaja-${var.run_id}-${each.value}-root"
      Role = each.value
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_put_response_hop_limit = 1
    http_tokens                 = "required"
    instance_metadata_tags      = "disabled"
  }

  lifecycle {
    precondition {
      condition     = var.root_volume_sizes_gib[each.value] >= local.ecs_ami_root_volume_size_gib
      error_message = "Singleton root volume is smaller than the pinned AMI root snapshot. Run preflight again."
    }
  }

  tags = {
    Name = "gaja-${var.run_id}-${each.value}"
    Role = each.value
  }

  depends_on = [
    terraform_data.bootstrap_prerequisites,
    aws_vpc_endpoint.interface,
    aws_vpc_endpoint.s3,
  ]
}
