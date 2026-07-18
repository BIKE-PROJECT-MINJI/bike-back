variable "aws_region" {
  description = "AWS region for ALB/ACM resources."
  type        = string
  default     = "ap-northeast-2"
}

variable "name_prefix" {
  description = "Resource name prefix."
  type        = string
  default     = "bike-api"
}

variable "vpc_id" {
  description = "VPC ID where the ALB and target instance live."
  type        = string
}

variable "public_subnet_ids" {
  description = "At least two public subnet IDs for the internet-facing ALB."
  type        = list(string)
}

variable "app_instance_id" {
  description = "EC2 instance ID currently running the Spring Boot app."
  type        = string
}

variable "app_instance_security_group_id" {
  description = "Security group ID attached to the backend EC2 instance."
  type        = string
}

variable "domain_name" {
  description = "Public API domain to attach to the ALB, e.g. api.gajabike.shop."
  type        = string
}

variable "health_check_path" {
  description = "Application health check path exposed by Spring Boot."
  type        = string
  default     = "/health"
}

variable "app_port" {
  description = "Spring Boot application port on the EC2 target."
  type        = number
  default     = 8080
}

variable "alb_ingress_cidrs" {
  description = "CIDR blocks allowed to reach the public ALB."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "create_route53_records" {
  description = "Whether Terraform should create Route53 validation and alias records. Keep false when DNS is managed in Gabia."
  type        = bool
  default     = false
}

variable "enable_https_listener" {
  description = "Create the HTTPS listener only after ACM validation is complete. Useful for Gabia/manual DNS flow."
  type        = bool
  default     = false
}

variable "route53_hosted_zone_id" {
  description = "Route53 hosted zone ID. Required only when create_route53_records=true."
  type        = string
  default     = null
}

variable "tags" {
  description = "Common tags for created resources."
  type        = map(string)
  default = {
    Project = "bike"
    Stack   = "api-edge"
    Managed = "terraform-draft"
  }
}
