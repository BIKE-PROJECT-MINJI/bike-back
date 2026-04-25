output "alb_dns_name" {
  description = "Public DNS name of the ALB."
  value       = aws_lb.api.dns_name
}

output "alb_zone_id" {
  description = "Canonical hosted zone ID of the ALB. Useful for Route53 alias records."
  value       = aws_lb.api.zone_id
}

output "https_endpoint" {
  description = "Expected HTTPS API endpoint after DNS is connected."
  value       = "https://${var.domain_name}"
}

output "certificate_validation_records" {
  description = "DNS records that must exist for ACM validation. Use these in Gabia when Route53 automation is disabled."
  value = [
    for dvo in aws_acm_certificate.api.domain_validation_options : {
      domain_name = dvo.domain_name
      name        = dvo.resource_record_name
      type        = dvo.resource_record_type
      value       = dvo.resource_record_value
    }
  ]
}

output "route53_alias_record_fqdn" {
  description = "Created Route53 alias FQDN when Route53 automation is enabled."
  value       = local.route53_enabled ? aws_route53_record.api_alias[0].fqdn : null
}
