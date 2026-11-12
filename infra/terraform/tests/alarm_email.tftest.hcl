mock_provider "aws" {}

# The three alarms email their owner through one SNS topic, both when they fire and when they recover.
# Without an address there is no topic and the alarms keep only the topics passed in.
# Run with: terraform init -backend=false && terraform test -filter=tests/alarm_email.tftest.hcl
# (on Windows the filter needs tests\alarm_email.tftest.hcl, or it runs 0 tests).
run "alarms_email_the_owner" {
  command = plan

  override_resource {
    target          = aws_sns_topic.alarms
    override_during = plan
    values = {
      arn = "arn:aws:sns:ca-central-1:123456789012:caselens-demo-alarms"
    }
  }

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    api_live_version       = "42"
    worker_live_version    = "43"
    relay_live_version     = "44"
    alarm_email            = "owner@example.com"
  }

  assert {
    condition     = aws_sns_topic_subscription.alarm_email[0].protocol == "email" && aws_sns_topic_subscription.alarm_email[0].endpoint == "owner@example.com"
    error_message = "The alarm topic must email the configured address."
  }

  assert {
    condition = alltrue([
      for actions in [
        aws_cloudwatch_metric_alarm.queue_depth.alarm_actions,
        aws_cloudwatch_metric_alarm.queue_depth.ok_actions,
        aws_cloudwatch_metric_alarm.dlq_depth.alarm_actions,
        aws_cloudwatch_metric_alarm.dlq_depth.ok_actions,
        aws_cloudwatch_metric_alarm.api_errors[0].alarm_actions,
        aws_cloudwatch_metric_alarm.api_errors[0].ok_actions,
      ] : actions == toset(["arn:aws:sns:ca-central-1:123456789012:caselens-demo-alarms"])
    ])
    error_message = "All three alarms must notify the email topic when they fire and when they recover."
  }
}

run "no_address_means_no_topic" {
  command = plan

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    api_live_version       = "42"
    worker_live_version    = "43"
    relay_live_version     = "44"
  }

  assert {
    condition     = length(aws_sns_topic.alarms) == 0 && length(aws_sns_topic_subscription.alarm_email) == 0
    error_message = "Without an address no topic or subscription is created."
  }

  assert {
    condition     = length(aws_cloudwatch_metric_alarm.dlq_depth.alarm_actions) == 0
    error_message = "Without an address the alarms keep only the topics passed in."
  }
}
