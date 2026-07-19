output "run_id" {
  value = var.run_id
}

output "alb_dns_name" {
  value = aws_lb.api.dns_name
}

output "gabia_cname" {
  value = {
    host   = trimsuffix(var.domain_name, ".")
    type   = "CNAME"
    target = aws_lb.api.dns_name
    ttl    = 60
  }
}

output "app_private_ips" {
  value = { for name, instance in aws_instance.app : name => instance.private_ip }
}

output "instance_ids" {
  value = {
    app       = { for name, instance in aws_instance.app : name => instance.id }
    singleton = { for role, instance in aws_instance.singleton : role => instance.id }
  }
}

output "singleton_private_ips" {
  value = { for role, instance in aws_instance.singleton : role => instance.private_ip }
}

output "target_group_arn" {
  value = aws_lb_target_group.api.arn
}

output "cleanup_schedule_arn" {
  value = aws_scheduler_schedule.cleanup.arn
}

output "cleanup_function_arn" {
  value = aws_lambda_function.cleanup.arn
}

output "artifact_prefix" {
  value = local.artifact_prefix
}

output "verification_boundary" {
  value = "Gabia CNAME must be updated manually. App targets remain detached until attach_app_targets=true after /ready, GraphHopper sample route, and AI worker health pass."
}
