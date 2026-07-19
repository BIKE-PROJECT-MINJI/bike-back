data "archive_file" "cleanup" {
  type        = "zip"
  source_file = "${path.module}/lambda/cleanup.py"
  output_path = "${path.module}/.artifacts/cleanup.zip"
}

resource "aws_lambda_function" "cleanup" {
  function_name = "gaja-${var.run_id}-cleanup"
  role          = aws_iam_role.cleanup.arn
  runtime       = "python3.12"
  handler       = "cleanup.handler"
  timeout       = 900
  memory_size   = 256

  filename         = data.archive_file.cleanup.output_path
  source_code_hash = data.archive_file.cleanup.output_base64sha256

  environment {
    variables = {
      RUN_ID          = var.run_id
      ARTIFACT_BUCKET = var.artifact_bucket_name
      ARTIFACT_PREFIX = local.artifact_prefix
      SECRET_PREFIX   = var.secret_parameter_prefix
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.cleanup,
    aws_iam_role_policy.cleanup,
  ]
}

resource "aws_scheduler_schedule" "cleanup" {
  name                         = "gaja-${var.run_id}-cleanup"
  schedule_expression          = "at(${var.cleanup_start_at})"
  schedule_expression_timezone = "UTC"
  action_after_completion      = "DELETE"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_lambda_function.cleanup.arn
    role_arn = aws_iam_role.scheduler.arn
    input    = jsonencode({ run_id = var.run_id })

    retry_policy {
      maximum_event_age_in_seconds = 900
      maximum_retry_attempts       = 3
    }
  }
}

resource "aws_lambda_permission" "scheduler" {
  statement_id  = "AllowSchedulerInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.cleanup.function_name
  principal     = "scheduler.amazonaws.com"
  source_arn    = aws_scheduler_schedule.cleanup.arn
}
