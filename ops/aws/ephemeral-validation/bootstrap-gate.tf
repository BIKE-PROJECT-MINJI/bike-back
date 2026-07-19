locals {
  destroy_authorization_file = "${path.module}/.artifacts/destroy-authorized"
  destroy_authorized = !var.destroy_mode || try(
    trimspace(file(local.destroy_authorization_file)) == var.run_id,
    false
  )
}

data "external" "bootstrap_prerequisites" {
  count   = var.destroy_mode ? 0 : 1
  program = ["python3", "${path.module}/scripts/check-bootstrap-prerequisites.py"]

  query = {
    aws_region      = var.aws_region
    artifact_bucket = var.artifact_bucket_name
    artifact_prefix = local.artifact_prefix
    secret_prefix   = var.secret_parameter_prefix
    run_id          = var.run_id
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
      condition = local.destroy_authorized && (
        var.destroy_mode || data.external.bootstrap_prerequisites[0].result.ready == "true"
      )
      error_message = "Run-scoped prerequisites are required for apply, and destroy_mode requires the run-matched CLI authorization file."
    }
  }
}
