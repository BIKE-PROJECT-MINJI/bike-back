data "external" "bootstrap_prerequisites" {
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
  input = data.external.bootstrap_prerequisites.result

  lifecycle {
    precondition {
      condition     = data.external.bootstrap_prerequisites.result.ready == "true"
      error_message = "Run-scoped cleanup, artifacts, and SecureString prerequisites must exist before EC2 apply."
    }
  }
}
