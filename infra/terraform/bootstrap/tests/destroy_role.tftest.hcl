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

  # Exact allowlists, so a wildcard such as iam:Put* or any action the teardown does not call fails the test. The
  # lists come from the AWS provider v5.100.0 refresh and destroy paths (see the comments in main.tf).
  assert {
    condition = alltrue([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement :
      statement.Effect != "Allow" || alltrue([for action in try(statement.Action, []) : !strcontains(action, "*")])
    ])
    error_message = "No allowed action may be a wildcard."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "iam:")
      ]) == toset([
      "iam:GetRole", "iam:ListRolePolicies", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies",
      "iam:ListInstanceProfilesForRole", "iam:DeleteRolePolicy", "iam:DetachRolePolicy", "iam:DeleteRole",
      "iam:ListOpenIDConnectProviders", "iam:ListRoles", "iam:GetOpenIDConnectProvider",
    ])
    error_message = "The iam actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "s3:")
      ]) == toset([
      "s3:GetBucketLocation", "s3:GetObject", "s3:PutObject", "s3:ListBucket", "s3:GetBucketAcl", "s3:GetBucketCORS",
      "s3:GetBucketWebsite", "s3:GetBucketVersioning", "s3:GetAccelerateConfiguration", "s3:GetBucketRequestPayment",
      "s3:GetBucketLogging", "s3:GetLifecycleConfiguration", "s3:GetReplicationConfiguration",
      "s3:GetEncryptionConfiguration", "s3:GetBucketObjectLockConfiguration", "s3:GetBucketTagging",
      "s3:GetBucketPolicy", "s3:GetBucketPublicAccessBlock", "s3:ListBucketVersions", "s3:DeleteBucketPolicy",
      "s3:PutBucketPublicAccessBlock", "s3:PutEncryptionConfiguration", "s3:PutBucketVersioning", "s3:DeleteBucket",
      "s3:DeleteObject", "s3:DeleteObjectVersion",
    ])
    error_message = "The s3 actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "sqs:")
      ]) == toset([
      "sqs:GetQueueAttributes", "sqs:ListQueueTags", "sqs:DeleteQueue",
    ])
    error_message = "The sqs actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "lambda:")
      ]) == toset([
      "lambda:GetFunction", "lambda:GetFunctionCodeSigningConfig", "lambda:ListVersionsByFunction",
      "lambda:GetAlias", "lambda:GetPolicy", "lambda:GetEventSourceMapping", "lambda:ListTags",
      "lambda:RemovePermission", "lambda:DeleteAlias", "lambda:DeleteEventSourceMapping", "lambda:DeleteFunction",
    ])
    error_message = "The lambda actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "logs:")
      ]) == toset([
      "logs:DescribeLogGroups", "logs:ListTagsForResource", "logs:DeleteLogGroup",
    ])
    error_message = "The logs actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "secretsmanager:")
      ]) == toset([
      "secretsmanager:DescribeSecret", "secretsmanager:GetResourcePolicy", "secretsmanager:DeleteSecret",
    ])
    error_message = "The secretsmanager actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "events:")
      ]) == toset([
      "events:DescribeRule", "events:ListTagsForResource", "events:ListTargetsByRule", "events:RemoveTargets",
      "events:DeleteRule",
    ])
    error_message = "The events actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "cloudwatch:")
      ]) == toset([
      "cloudwatch:DescribeAlarms", "cloudwatch:ListTagsForResource", "cloudwatch:DeleteAlarms",
    ])
    error_message = "The cloudwatch actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "sns:")
      ]) == toset([
      "sns:GetTopicAttributes", "sns:ListTagsForResource", "sns:GetSubscriptionAttributes",
      "sns:ListSubscriptionsByTopic", "sns:Unsubscribe", "sns:DeleteTopic",
    ])
    error_message = "The sns actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "apigateway:")
      ]) == toset([
      "apigateway:GET", "apigateway:DELETE",
    ])
    error_message = "The apigateway actions must be exactly the reads, deletes and disables the teardown calls."
  }

  assert {
    condition = toset([
      for action in flatten([for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : try(statement.Action, []) if statement.Effect == "Allow"]) :
      action if startswith(action, "cloudfront:")
      ]) == toset([
      "cloudfront:GetDistribution", "cloudfront:ListTagsForResource", "cloudfront:UpdateDistribution",
      "cloudfront:DeleteDistribution", "cloudfront:GetOriginAccessControl", "cloudfront:DeleteOriginAccessControl",
      "cloudfront:GetResponseHeadersPolicy", "cloudfront:DeleteResponseHeadersPolicy", "cloudfront:GetCachePolicy",
      "cloudfront:ListCachePolicies",
    ])
    error_message = "The cloudfront actions must be exactly the reads, deletes and disables the teardown calls."
  }

  # Disabling and deleting a distribution are limited to this app's tagged distribution.
  assert {
    condition = length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if statement.Effect == "Allow" && contains(try(statement.Action, []), "cloudfront:UpdateDistribution")
      && try(statement.Condition.StringEquals["aws:ResourceTag/Project"], "") == "caselens"
      && try(statement.Condition.StringEquals["aws:ResourceTag/Environment"], "") == "demo"
      ]) == 1 && length([
      for statement in jsondecode(aws_iam_role_policy.github_destroy.policy).Statement : statement
      if statement.Effect == "Allow" && anytrue([for action in try(statement.Action, []) : contains(["cloudfront:UpdateDistribution", "cloudfront:DeleteDistribution"], action)])
    ]) == 1
    error_message = "UpdateDistribution and DeleteDistribution must require this app's Project and Environment tags."
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
