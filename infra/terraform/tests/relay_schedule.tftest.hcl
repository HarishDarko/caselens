mock_provider "aws" {}

run "relay_recovery_allows_neon_to_scale_to_zero" {
  command = plan

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    api_live_version       = "42"
    worker_live_version    = "43"
  }

  assert {
    condition     = aws_cloudwatch_event_rule.relay_schedule[0].schedule_expression == "rate(30 minutes)"
    error_message = "The recovery relay must leave enough idle time for Neon to scale to zero."
  }
}
