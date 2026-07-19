resource "aws_security_group" "endpoint" {
  name_prefix = "gaja-${var.run_id}-endpoint-"
  description = "Private interface endpoint ingress"
  vpc_id      = aws_vpc.run.id

  tags = {
    Name = "gaja-${var.run_id}-endpoint-sg"
  }
}

resource "aws_security_group" "node" {
  for_each = setunion(local.singleton_roles, toset(["app"]))

  name_prefix = "gaja-${var.run_id}-${each.value}-"
  description = "Private ${each.value} node"
  vpc_id      = aws_vpc.run.id

  tags = {
    Name = "gaja-${var.run_id}-${each.value}-sg"
    Role = each.value
  }
}

resource "aws_vpc_security_group_ingress_rule" "endpoint_from_nodes" {
  for_each = aws_security_group.node

  security_group_id            = aws_security_group.endpoint.id
  referenced_security_group_id = each.value.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "${each.key} to AWS private endpoints"
}

resource "aws_vpc_security_group_egress_rule" "node_to_endpoints" {
  for_each = aws_security_group.node

  security_group_id            = each.value.id
  referenced_security_group_id = aws_security_group.endpoint.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "AWS private endpoints only"
}

resource "aws_vpc_security_group_egress_rule" "node_to_s3" {
  for_each = aws_security_group.node

  security_group_id = each.value.id
  prefix_list_id    = data.aws_prefix_list.s3.id
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "Offline artifacts through S3 gateway endpoint"
}

resource "aws_vpc_security_group_egress_rule" "node_dns_udp" {
  for_each = aws_security_group.node

  security_group_id = each.value.id
  cidr_ipv4         = aws_vpc.run.cidr_block
  from_port         = 53
  to_port           = 53
  ip_protocol       = "udp"
  description       = "VPC DNS resolver"
}

resource "aws_vpc_security_group_egress_rule" "node_dns_tcp" {
  for_each = aws_security_group.node

  security_group_id = each.value.id
  cidr_ipv4         = aws_vpc.run.cidr_block
  from_port         = 53
  to_port           = 53
  ip_protocol       = "tcp"
  description       = "VPC DNS resolver fallback"
}

locals {
  app_dependencies = {
    db = {
      port = 5432
      sg   = aws_security_group.node["db"].id
    }
    redis = {
      port = 6379
      sg   = aws_security_group.node["redis"].id
    }
    graphhopper = {
      port = 8989
      sg   = aws_security_group.node["graphhopper"].id
    }
  }
}

resource "aws_vpc_security_group_egress_rule" "app_to_dependency" {
  for_each = local.app_dependencies

  security_group_id            = aws_security_group.node["app"].id
  referenced_security_group_id = each.value.sg
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
  description                  = "App to ${each.key}"
}

resource "aws_vpc_security_group_ingress_rule" "dependency_from_app" {
  for_each = local.app_dependencies

  security_group_id            = each.value.sg
  referenced_security_group_id = aws_security_group.node["app"].id
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
  description                  = "${each.key} from app only"
}

resource "aws_vpc_security_group_egress_rule" "load_to_app" {
  security_group_id            = aws_security_group.node["load"].id
  referenced_security_group_id = aws_security_group.node["app"].id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Private k6 to app"
}

resource "aws_vpc_security_group_ingress_rule" "app_from_load" {
  security_group_id            = aws_security_group.node["app"].id
  referenced_security_group_id = aws_security_group.node["load"].id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Private k6 direct baseline"
}

resource "aws_vpc_security_group_egress_rule" "observability_to_app" {
  security_group_id            = aws_security_group.node["observability"].id
  referenced_security_group_id = aws_security_group.node["app"].id
  from_port                    = 18081
  to_port                      = 18081
  ip_protocol                  = "tcp"
  description                  = "Prometheus scrape to app management port"
}

resource "aws_vpc_security_group_ingress_rule" "app_from_observability" {
  security_group_id            = aws_security_group.node["app"].id
  referenced_security_group_id = aws_security_group.node["observability"].id
  from_port                    = 18081
  to_port                      = 18081
  ip_protocol                  = "tcp"
  description                  = "Private management scrape"
}
