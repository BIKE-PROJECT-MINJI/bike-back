data "external" "bootstrap_prerequisites" {
  count   = var.destroy_mode ? 0 : 1
  program = ["python3", "${path.module}/scripts/check-bootstrap-prerequisites.py"]

  query = {
    aws_region      = var.aws_region
    artifact_bucket = var.artifact_bucket_name
    artifact_prefix = local.artifact_prefix
    secret_prefix   = var.secret_parameter_prefix
    schedule_name   = "gaja-${var.run_id}-cleanup"
    cleanup_at      = var.cleanup_start_at
  }
}

resource "terraform_data" "bootstrap_prerequisites" {
  input = var.destroy_mode ? {
    ready  = "destroy"
    run_id = var.run_id
  } : data.external.bootstrap_prerequisites[0].result

  lifecycle {
    precondition {
      condition     = var.destroy_mode || data.external.bootstrap_prerequisites[0].result.ready == "true"
      error_message = "Run-scoped cleanup, artifacts, and SecureString prerequisites must exist before EC2 apply."
    }
  }
}
