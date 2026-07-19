resource "aws_vpc" "run" {
  cidr_block           = "10.88.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "gaja-${var.run_id}-vpc"
  }
}

resource "aws_internet_gateway" "run" {
  vpc_id = aws_vpc.run.id

  tags = {
    Name = "gaja-${var.run_id}-igw"
  }
}

resource "aws_subnet" "public" {
  for_each = {
    for index, availability_zone in local.availability_zones : tostring(index) => availability_zone
  }

  vpc_id                  = aws_vpc.run.id
  availability_zone       = each.value
  cidr_block              = cidrsubnet(aws_vpc.run.cidr_block, 8, tonumber(each.key))
  map_public_ip_on_launch = false

  tags = {
    Name = "gaja-${var.run_id}-public-${each.key}"
    Tier = "public-edge-only"
  }
}

resource "aws_subnet" "private" {
  for_each = {
    for index, availability_zone in local.availability_zones : tostring(index) => availability_zone
  }

  vpc_id                  = aws_vpc.run.id
  availability_zone       = each.value
  cidr_block              = cidrsubnet(aws_vpc.run.cidr_block, 8, 10 + tonumber(each.key))
  map_public_ip_on_launch = false

  tags = {
    Name = "gaja-${var.run_id}-private-${each.key}"
    Tier = "private-no-nat"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.run.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.run.id
  }

  tags = {
    Name = "gaja-${var.run_id}-public-rt"
  }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.run.id

  tags = {
    Name = "gaja-${var.run_id}-private-rt"
  }
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}

data "aws_prefix_list" "s3" {
  name = "com.amazonaws.${var.aws_region}.s3"
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.run.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = "*"
      Action    = ["s3:GetObject", "s3:ListBucket", "s3:PutObject"]
      Resource = [
        "arn:aws:s3:::${var.artifact_bucket_name}",
        "arn:aws:s3:::${var.artifact_bucket_name}/${local.artifact_prefix}/*",
      ]
    }]
  })

  tags = {
    Name = "gaja-${var.run_id}-s3-endpoint"
  }

  depends_on = [aws_scheduler_schedule.cleanup]
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_endpoint_services

  vpc_id              = aws_vpc.run.id
  service_name        = "com.amazonaws.${var.aws_region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = [aws_subnet.private["0"].id]
  security_group_ids  = [aws_security_group.endpoint.id]

  tags = {
    Name = "gaja-${var.run_id}-${each.value}-endpoint"
  }

  depends_on = [aws_scheduler_schedule.cleanup]
}
