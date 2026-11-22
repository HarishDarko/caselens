mock_provider "aws" {
  mock_data "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

# The destroy plan refreshes every resource and data source before it deletes anything. The list reads that AWS
# accepts only on "*" must be granted on "*", or teardown fails with AccessDenied at the plan step.
# Run with: terraform init -backend=false && terraform test (from infra/terraform/bootstrap).
run "destroy_role_can_refresh_before_destroying" {
  command = plan

  variables {
    bucket_name = "caselens-test-state"
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if statement.Resource == "*" && length(setsubtract(["logs:DescribeLogGroups", "iam:ListOpenIDConnectProviders"], statement.Action)) == 0
    ]) == 1
    error_message = "logs:DescribeLogGroups and iam:ListOpenIDConnectProviders need Resource \"*\" for the destroy plan's refresh."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if statement.Resource == "*" && contains(statement.Action, "cloudwatch:DescribeAlarms")
    ]) == 1
    error_message = "cloudwatch:DescribeAlarms must be allowed on \"*\"."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if contains(statement.Action, "iam:GetOpenIDConnectProvider") && statement.Resource == "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    ]) == 1
    error_message = "iam:GetOpenIDConnectProvider must name only the GitHub OIDC provider."
  }

  # Every write or delete grant stays scoped: only list and describe reads, plus the services that have no
  # resource-level scoping (API Gateway and CloudFront), may use "*".
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement :
      statement.Resource != "*" || length(setsubtract(statement.Action, [
        "logs:DescribeLogGroups", "iam:ListOpenIDConnectProviders", "iam:ListRoles", "cloudwatch:DescribeAlarms",
        "apigateway:*", "cloudfront:*",
      ])) == 0
    ])
    error_message = "Only the documented list reads (and apigateway/cloudfront) may be granted on \"*\"."
  }
}
