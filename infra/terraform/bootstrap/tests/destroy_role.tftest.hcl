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
      if statement.Resource == "*" && length(setsubtract(["logs:DescribeLogGroups", "iam:ListOpenIDConnectProviders"], try(statement.Action, []))) == 0
    ]) == 1
    error_message = "logs:DescribeLogGroups and iam:ListOpenIDConnectProviders need Resource \"*\" for the destroy plan's refresh."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if contains(try(statement.Action, []), "iam:GetOpenIDConnectProvider") && statement.Resource == "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    ]) == 1
    error_message = "iam:GetOpenIDConnectProvider must name only the GitHub OIDC provider."
  }

  # Every grant stays scoped: only list reads that AWS accepts on nothing narrower may use "*".
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement :
      statement.Effect != "Allow" || statement.Resource != "*" || length(setsubtract(try(statement.Action, []), [
        "logs:DescribeLogGroups", "iam:ListOpenIDConnectProviders", "iam:ListRoles", "cloudfront:ListCachePolicies",
      ])) == 0
    ])
    error_message = "Only the documented list reads may be granted on \"*\"."
  }

  # The destroy role must not be able to change IAM: no wildcard and no create, put, attach, pass or trust change.
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement :
      statement.Effect != "Allow" || length(setintersection(try(statement.Action, []), [
        "iam:*", "iam:CreateRole", "iam:PutRolePolicy", "iam:AttachRolePolicy", "iam:PassRole", "iam:UpdateAssumeRolePolicy",
        "iam:UpdateRole", "iam:PutRolePermissionsBoundary", "iam:TagRole",
      ])) == 0
    ])
    error_message = "The destroy role may only read and delete IAM roles."
  }

  # It cannot touch itself at all, and on the deploy role it can only read and delete.
  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if statement.Effect == "Deny" && try(statement.Action, []) == ["iam:*"] && statement.Resource == "arn:aws:iam::123456789012:role/caselens-demo-terraform-destroy"
    ]) == 1
    error_message = "The destroy role must deny itself every IAM action."
  }

  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if statement.Effect == "Deny" && statement.Resource == "arn:aws:iam::123456789012:role/caselens-demo-github-deploy" && !contains(try(statement.NotAction, []), "iam:PutRolePolicy")
    ]) == 1
    error_message = "The destroy role must deny every IAM change to the deploy role except its teardown."
  }

  # API Gateway and CloudFront are limited to this region's HTTP APIs and this account's CloudFront resources.
  assert {
    condition = alltrue(flatten([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : [
        startswith(statement.Resource, "arn:aws:apigateway:ca-central-1::/apis/")
      ] if statement.Effect == "Allow" && anytrue([for action in try(statement.Action, []) : startswith(action, "apigateway:")])
    ]))
    error_message = "API Gateway must be scoped to this region's HTTP APIs."
  }

  assert {
    condition = alltrue(flatten([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : [
        try(statement.Action, []) == ["cloudfront:ListCachePolicies"] || can(regex("^arn:aws:cloudfront::(123456789012|[*]):(distribution|origin-access-control|response-headers-policy|cache-policy)/[*]$", statement.Resource))
      ] if statement.Effect == "Allow" && anytrue([for action in try(statement.Action, []) : startswith(action, "cloudfront:")])
    ]))
    error_message = "CloudFront must be scoped to this account's distribution, OAC, response headers and cache policy ARNs."
  }
}
