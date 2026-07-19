data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

resource "aws_iam_role" "node" {
  name = "gaja-${var.run_id}-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "node_run_access" {
  name = "gaja-${var.run_id}-run-access"
  role = aws_iam_role.node.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SsmAgentControlPlaneWithoutGlobalParameterRead"
        Effect = "Allow"
        Action = [
          "ec2messages:AcknowledgeMessage",
          "ec2messages:DeleteMessage",
          "ec2messages:FailMessage",
          "ec2messages:GetEndpoint",
          "ec2messages:GetMessages",
          "ec2messages:SendReply",
          "ssm:DescribeAssociation",
          "ssm:DescribeDocument",
          "ssm:GetDeployablePatchSnapshotForInstance",
          "ssm:GetDocument",
          "ssm:GetManifest",
          "ssm:ListAssociations",
          "ssm:ListInstanceAssociations",
          "ssm:PutComplianceItems",
          "ssm:PutConfigurePackageResult",
          "ssm:PutInventory",
          "ssm:UpdateAssociationStatus",
          "ssm:UpdateInstanceAssociationStatus",
          "ssm:UpdateInstanceInformation",
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel",
        ]
        Resource = ["*"]
      },
      {
        Sid    = "ReadOfflineArtifacts"
        Effect = "Allow"
        Action = ["s3:GetObject"]
        Resource = [
          "arn:${data.aws_partition.current.partition}:s3:::${var.artifact_bucket_name}/${local.artifact_prefix}/*",
        ]
      },
      {
        Sid      = "ListOfflineArtifacts"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = ["arn:${data.aws_partition.current.partition}:s3:::${var.artifact_bucket_name}"]
        Condition = {
          StringLike = {
            "s3:prefix" = ["${local.artifact_prefix}/*"]
          }
        }
      },
      {
        Sid    = "WriteRedactedRunEvidence"
        Effect = "Allow"
        Action = ["s3:PutObject"]
        Resource = [
          "arn:${data.aws_partition.current.partition}:s3:::${var.artifact_bucket_name}/${local.artifact_prefix}/evidence/*",
        ]
      },
      {
        Sid    = "ReadRunSecrets"
        Effect = "Allow"
        Action = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = [
          "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.secret_parameter_prefix}*",
        ]
      },
      {
        Sid      = "DecryptRunSecretsOnlyThroughSsm"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = ["*"]
        Condition = {
          StringEquals = {
            "kms:ViaService" = "ssm.${var.aws_region}.amazonaws.com"
          }
          StringLike = {
            "kms:EncryptionContext:PARAMETER_ARN" = "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.secret_parameter_prefix}*"
          }
        }
      },
    ]
  })
}

resource "aws_iam_instance_profile" "node" {
  name = "gaja-${var.run_id}-node"
  role = aws_iam_role.node.name
}

resource "aws_iam_role" "cleanup" {
  name = "gaja-${var.run_id}-cleanup"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "cleanup" {
  name = "gaja-${var.run_id}-cleanup"
  role = aws_iam_role.cleanup.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "TerminateTaggedInstances"
        Effect   = "Allow"
        Action   = ["ec2:TerminateInstances"]
        Resource = ["*"]
        Condition = {
          StringEquals = {
            "ec2:ResourceTag/RunId" = var.run_id
          }
        }
      },
      {
        Sid    = "DescribeCleanupTargets"
        Effect = "Allow"
        Action = [
          "ec2:DescribeInstances",
          "ec2:DescribeAddresses",
          "ec2:DescribeNatGateways",
          "ec2:DescribeVolumes",
          "ec2:DescribeVpcEndpoints",
          "elasticloadbalancing:DescribeLoadBalancers",
          "elasticloadbalancing:DescribeTags",
        ]
        Resource = ["*"]
      },
      {
        Sid    = "DeleteTaggedResidualComputeResources"
        Effect = "Allow"
        Action = [
          "ec2:DeleteNatGateway",
          "ec2:DeleteVolume",
          "ec2:ReleaseAddress",
        ]
        Resource = ["*"]
        Condition = {
          StringEquals = {
            "ec2:ResourceTag/RunId" = var.run_id
          }
        }
      },
      {
        Sid      = "DeleteTaggedVpcEndpoints"
        Effect   = "Allow"
        Action   = ["ec2:DeleteVpcEndpoints"]
        Resource = ["*"]
        Condition = {
          StringEquals = {
            "ec2:ResourceTag/RunId" = var.run_id
          }
        }
      },
      {
        Sid      = "DeleteTaggedLoadBalancer"
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:DeleteLoadBalancer"]
        Resource = ["*"]
        Condition = {
          StringEquals = {
            "aws:ResourceTag/RunId" = var.run_id
          }
        }
      },
      {
        Sid    = "DeleteRunParameters"
        Effect = "Allow"
        Action = ["ssm:DeleteParameters", "ssm:GetParametersByPath"]
        Resource = [
          "arn:${data.aws_partition.current.partition}:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter${var.secret_parameter_prefix}*",
        ]
      },
      {
        Sid    = "DeleteRunArtifacts"
        Effect = "Allow"
        Action = ["s3:DeleteBucket", "s3:DeleteObject", "s3:ListBucket"]
        Resource = [
          "arn:${data.aws_partition.current.partition}:s3:::${var.artifact_bucket_name}",
          "arn:${data.aws_partition.current.partition}:s3:::${var.artifact_bucket_name}/${local.artifact_prefix}/*",
        ]
      },
      {
        Sid      = "WriteCleanupLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.cleanup.arn}:*"]
      },
    ]
  })
}

resource "aws_iam_role" "scheduler" {
  name = "gaja-${var.run_id}-scheduler"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "scheduler.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  name = "gaja-${var.run_id}-invoke-cleanup"
  role = aws_iam_role.scheduler.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["lambda:InvokeFunction"]
      Resource = [aws_lambda_function.cleanup.arn]
    }]
  })
}

resource "aws_cloudwatch_log_group" "cleanup" {
  name              = "/aws/lambda/gaja-${var.run_id}-cleanup"
  retention_in_days = 1
}
