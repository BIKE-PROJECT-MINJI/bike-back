variable "aws_region" {
  description = "AWS region for the isolated validation run."
  type        = string
  default     = "ap-northeast-2"
}

variable "run_id" {
  description = "Unique owner tag and artifact prefix for one disposable run."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{5,39}$", var.run_id))
    error_message = "run_id must be 6-40 lowercase letters, digits, or hyphens."
  }
}

variable "domain_name" {
  description = "Gabia-managed API FQDN whose CNAME will point to the run ALB."
  type        = string
  default     = "api.gajabike.shop"
}

variable "existing_acm_certificate_arn" {
  description = "Existing ISSUED ACM certificate ARN. This stack never creates a certificate."
  type        = string
}

variable "artifact_bucket_name" {
  description = "Preflight-created encrypted S3 bucket containing offline artifacts."
  type        = string
}

variable "bootstrap_object_prefix" {
  description = "S3 prefix containing per-role bootstrap scripts and image archives."
  type        = string
  default     = "runs"
}

variable "secret_parameter_prefix" {
  description = "Run-scoped SSM SecureString path populated outside Terraform."
  type        = string

  validation {
    condition     = startswith(var.secret_parameter_prefix, "/gaja/ephemeral/") && endswith(var.secret_parameter_prefix, "/")
    error_message = "secret_parameter_prefix must be a run-scoped /gaja/ephemeral/.../ path."
  }
}

variable "cleanup_start_at" {
  description = "UTC timestamp for one-shot cleanup, formatted YYYY-MM-DDTHH:mm:ss."
  type        = string
}

variable "app_count" {
  description = "One app for Runs A/B, two apps for Run C."
  type        = number
  default     = 1

  validation {
    condition     = contains([1, 2], var.app_count)
    error_message = "app_count must be 1 or 2."
  }
}

variable "attach_app_targets" {
  description = "Attach app nodes only after /ready and provider gates pass."
  type        = bool
  default     = false
}

variable "instance_types" {
  description = "Role sizes are intentionally capped at t3.small."
  type        = map(string)
  default = {
    app           = "t3.small"
    db            = "t3.small"
    redis         = "t3.micro"
    graphhopper   = "t3.small"
    load          = "t3.micro"
    observability = "t3.small"
  }

  validation {
    condition = alltrue([
      for instance_type in values(var.instance_types) : contains(["t3.micro", "t3.small"], instance_type)
    ])
    error_message = "Only t3.micro and t3.small are allowed."
  }
}

variable "root_volume_sizes_gib" {
  description = "Encrypted gp3 root volume sizes; no role may exceed 30 GiB."
  type        = map(number)
  default = {
    app           = 16
    db            = 20
    redis         = 8
    graphhopper   = 20
    load          = 8
    observability = 16
  }

  validation {
    condition     = alltrue([for size in values(var.root_volume_sizes_gib) : size >= 8 && size <= 30])
    error_message = "Root volumes must be between 8 and 30 GiB."
  }
}

variable "ttl_minutes" {
  description = "Hard paid-resource lifetime."
  type        = number
  default     = 180

  validation {
    condition     = var.ttl_minutes == 180
    error_message = "The approved hard TTL is exactly 180 minutes."
  }
}

variable "cost_limit_usd" {
  description = "Maximum estimated run cost after headroom."
  type        = number
  default     = 3

  validation {
    condition     = var.cost_limit_usd > 0 && var.cost_limit_usd <= 3
    error_message = "Cost limit must be greater than zero and at most USD 3."
  }
}

variable "tags" {
  description = "Additional non-secret tags."
  type        = map(string)
  default     = {}
}
