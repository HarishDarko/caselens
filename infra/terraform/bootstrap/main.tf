terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

locals {
  application_name  = "${var.project_name}-${var.environment}"
  destroy_role_name = "${local.application_name}-terraform-destroy"
  # The deploy role is a main-stack resource (infra/terraform/main.tf), so the teardown reads and deletes it.
  deploy_role_name = "${local.application_name}-github-deploy"

  # The exact actions the destroy role may use on the application's resources. tests/destroy_role.tftest.hcl
  # asserts each list, so a wildcard or a new write action fails the test.
  destroy_actions = {
    s3_bucket = [
      "s3:ListBucket", "s3:GetBucketAcl", "s3:GetBucketCORS", "s3:GetBucketWebsite", "s3:GetBucketVersioning",
      "s3:GetAccelerateConfiguration", "s3:GetBucketRequestPayment", "s3:GetBucketLogging", "s3:GetLifecycleConfiguration",
      "s3:GetReplicationConfiguration", "s3:GetEncryptionConfiguration", "s3:GetBucketObjectLockConfiguration",
      "s3:GetBucketTagging", "s3:GetBucketPolicy", "s3:GetBucketPublicAccessBlock", "s3:ListBucketVersions",
      "s3:DeleteBucketPolicy", "s3:PutBucketPublicAccessBlock", "s3:PutEncryptionConfiguration", "s3:PutBucketVersioning",
      "s3:DeleteBucket",
    ]
    s3_object = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
    sqs       = ["sqs:GetQueueAttributes", "sqs:ListQueueTags", "sqs:DeleteQueue"]
    lambda = [
      "lambda:GetFunction", "lambda:GetFunctionCodeSigningConfig", "lambda:ListVersionsByFunction", "lambda:GetAlias",
      "lambda:GetPolicy", "lambda:GetEventSourceMapping", "lambda:ListTags", "lambda:RemovePermission", "lambda:DeleteAlias",
      "lambda:DeleteEventSourceMapping", "lambda:DeleteFunction",
    ]
    logs                           = ["logs:ListTagsForResource", "logs:DeleteLogGroup"]
    secretsmanager                 = ["secretsmanager:DescribeSecret", "secretsmanager:GetResourcePolicy", "secretsmanager:DeleteSecret"]
    events                         = ["events:DescribeRule", "events:ListTagsForResource", "events:ListTargetsByRule", "events:RemoveTargets", "events:DeleteRule"]
    cloudwatch                     = ["cloudwatch:DescribeAlarms", "cloudwatch:ListTagsForResource", "cloudwatch:DeleteAlarms"]
    cloudfront_distribution_read   = ["cloudfront:GetDistribution", "cloudfront:ListTagsForResource"]
    cloudfront_distribution_change = ["cloudfront:UpdateDistribution", "cloudfront:DeleteDistribution"]
    iam_role = [
      "iam:GetRole", "iam:ListRolePolicies", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:ListInstanceProfilesForRole",
      "iam:DeleteRolePolicy", "iam:DetachRolePolicy", "iam:DeleteRole",
    ]
  }
}

data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "terraform_state" {
  bucket = var.bucket_name

  tags = {
    Project     = "caselens"
    Environment = "shared"
    ManagedBy   = "terraform-bootstrap"
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}


# Only the destroy job may assume this role. It runs in its own GitHub environment (production-destroy, with a
# required reviewer and main as the only deployment branch), so its OIDC subject is
# repo:<owner>/<repo>:environment:production-destroy. The deploy job runs in "production" and sends a different
# subject, so it cannot assume this role. A branch subject (repo:...:ref:refs/heads/...) is never sent for a job
# with an environment, and trusting one would let a workflow skip the reviewer.
resource "aws_iam_role" "github_destroy" {
  name = local.destroy_role_name

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:environment:${var.destroy_environment}"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_destroy" {
  role = aws_iam_role.github_destroy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket", "s3:GetBucketLocation"], Resource = "arn:aws:s3:::${var.bucket_name}" },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "arn:aws:s3:::${var.bucket_name}/caselens/demo/terraform.tfstate" },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = "arn:aws:s3:::${var.bucket_name}/caselens/demo/terraform.tfstate.tflock" },
      # Each application grant below is the exact list (local.destroy_actions) of read, delete and disable actions the
      # AWS provider v5.100.0 calls to refresh and destroy that resource. No create, update, policy, permission or code
      # changes. Service Authorization Reference pages are named per service:
      # https://docs.aws.amazon.com/service-authorization/latest/reference/reference_policies_actions-resources-contextkeys.html
      #
      # S3 (list_amazons3.html). Refresh: HeadBucket (ListBucket) and the bucket's Get* configuration, tags, policy and
      # public access block. Destroy: empty every object version (force_destroy), then delete the bucket. Three removals
      # have no Delete action of their own: AWS authorizes DeletePublicAccessBlock with PutBucketPublicAccessBlock and
      # DeleteBucketEncryption with PutEncryptionConfiguration, and the provider removes versioning by suspending it
      # (PutBucketVersioning). The role has no PutBucketPolicy, so it cannot open a bucket to anyone.
      # https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-with-s3-policy-actions.html
      { Effect = "Allow", Action = local.destroy_actions.s3_bucket, Resource = ["arn:aws:s3:::${local.application_name}", "arn:aws:s3:::${local.application_name}-artifacts"] },
      { Effect = "Allow", Action = local.destroy_actions.s3_object, Resource = ["arn:aws:s3:::${local.application_name}/*", "arn:aws:s3:::${local.application_name}-artifacts/*"] },
      # SQS (list_amazonsqs.html). GetQueueAttributes also serves the wait for deletion.
      { Effect = "Allow", Action = local.destroy_actions.sqs, Resource = "arn:aws:sqs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:${local.application_name}-*" },
      # Lambda (list_awslambda.html): functions, their aliases and permissions (qualified ARNs match the function
      # pattern), and the SQS event source mapping. Function tags come from GetFunction; ListTags is for the mapping.
      { Effect = "Allow", Action = local.destroy_actions.lambda, Resource = ["arn:aws:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:function:${local.application_name}-*", "arn:aws:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:event-source-mapping:*"] },
      # CloudWatch Logs (list_amazoncloudwatchlogs.html). DescribeLogGroups is granted on "*" further down.
      { Effect = "Allow", Action = local.destroy_actions.logs, Resource = ["arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/lambda/${local.application_name}-*", "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/apigateway/${local.application_name}-*"] },
      # Secrets Manager (list_awssecretsmanager.html). Tags come from DescribeSecret.
      { Effect = "Allow", Action = local.destroy_actions.secretsmanager, Resource = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${local.application_name}/*" },
      # EventBridge (list_amazoneventbridge.html): rules and their targets.
      { Effect = "Allow", Action = local.destroy_actions.events, Resource = "arn:aws:events:${var.aws_region}:${data.aws_caller_identity.current.account_id}:rule/${local.application_name}-*" },
      # CloudWatch (list_amazoncloudwatch.html): metric alarms, described by name.
      { Effect = "Allow", Action = local.destroy_actions.cloudwatch, Resource = "arn:aws:cloudwatch:${var.aws_region}:${data.aws_caller_identity.current.account_id}:alarm:${local.application_name}-*" },
      # The alarm email topic and its subscription (main stack, when alarm_email is set): read them during the
      # destroy plan, then unsubscribe and delete. Only this app's topics; topics passed in are never touched.
      { Effect = "Allow", Action = ["sns:GetTopicAttributes", "sns:ListTagsForResource", "sns:GetSubscriptionAttributes", "sns:ListSubscriptionsByTopic", "sns:Unsubscribe", "sns:DeleteTopic"], Resource = "arn:aws:sns:${var.aws_region}:${data.aws_caller_identity.current.account_id}:${local.application_name}-*" },
      # API Gateway v2: the destroy only reads (GET) and deletes (DELETE) the HTTP API and its integration, route and
      # stage, all under /apis/<id> in this region. The provider reads API and stage tags from GetApi and GetStage, so
      # no /tags path is needed. https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazonapigatewaymanagementv2.html
      { Effect = "Allow", Action = ["apigateway:GET", "apigateway:DELETE"], Resource = "arn:aws:apigateway:${var.aws_region}::/apis/*" },
      # CloudFront: read, disable (UpdateDistribution) and delete the distribution, its origin access control and its
      # response headers policy, and read the managed cache policy the distribution uses. The main stack's IDs live in
      # another state, so each is scoped to its resource type in this account. Every action below takes a resource
      # type except ListCachePolicies (the cache policy data source looks the policy up by name), which needs "*".
      # The provider's disable, wait and delete flow reads the distribution and its ETag with GetDistribution only.
      # https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazoncloudfront.html
      { Effect = "Allow", Action = local.destroy_actions.cloudfront_distribution_read, Resource = "arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/*" },
      # Disabling and deleting change a distribution, so they are limited to this app's own. The bootstrap stack cannot
      # know the distribution ID (it lives in the main stack's state), but UpdateDistribution and DeleteDistribution
      # support aws:ResourceTag, and the main stack's default_tags put Project and Environment on the distribution.
      # This role cannot tag anything, so it cannot bring another distribution into scope.
      {
        Effect    = "Allow"
        Action    = local.destroy_actions.cloudfront_distribution_change
        Resource  = "arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/*"
        Condition = { StringEquals = { "aws:ResourceTag/Project" = var.project_name, "aws:ResourceTag/Environment" = var.environment } }
      },
      { Effect = "Allow", Action = ["cloudfront:GetOriginAccessControl", "cloudfront:DeleteOriginAccessControl"], Resource = "arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:origin-access-control/*" },
      { Effect = "Allow", Action = ["cloudfront:GetResponseHeadersPolicy", "cloudfront:DeleteResponseHeadersPolicy"], Resource = "arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:response-headers-policy/*" },
      # Managed-CachingOptimized is owned by AWS, not this account, so the account part of its ARN is left open. Read only.
      { Effect = "Allow", Action = ["cloudfront:GetCachePolicy"], Resource = "arn:aws:cloudfront::*:cache-policy/*" },
      { Effect = "Allow", Action = ["cloudfront:ListCachePolicies"], Resource = "*" },
      # IAM on the app's roles (lambda runtime, deploy): only what the refresh reads and the destroy deletes. The
      # provider reads tags from GetRole, so ListRoleTags is not needed. No create, put, attach, pass or trust changes.
      # https://docs.aws.amazon.com/service-authorization/latest/reference/list_awsidentityandaccessmanagementiam.html
      { Effect = "Allow", Action = local.destroy_actions.iam_role, Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.application_name}-*" },
      # The role name pattern above also matches this role. It is not in the application state, so it never needs IAM
      # on itself: deny all of it, so it can never read, rewrite or delete its own policy or trust.
      { Effect = "Deny", Action = ["iam:*"], Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.destroy_role_name}" },
      # The deploy role is torn down with the app, so it keeps the reads and deletes above. Deny every other IAM action
      # on it, so the destroy role can never change what the deploy role may do.
      { Effect = "Deny", NotAction = local.destroy_actions.iam_role, Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.deploy_role_name}" },
      # `terraform plan -destroy` first refreshes every resource and reads every data source, so each read below must
      # be allowed or the run stops before anything is deleted. These three list actions have no resource type in
      # the Service Authorization Reference, so AWS accepts them only on "*":
      # - logs:DescribeLogGroups: the provider reads aws_cloudwatch_log_group with DescribeLogGroups (name prefix).
      #   https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazoncloudwatchlogs.html
      # - iam:ListOpenIDConnectProviders: data.aws_iam_openid_connect_provider.github is looked up by URL, which
      #   lists the account's providers first. iam:ListRoles was already here.
      #   https://docs.aws.amazon.com/service-authorization/latest/reference/list_awsidentityandaccessmanagementiam.html
      { Effect = "Allow", Action = ["logs:DescribeLogGroups", "iam:ListOpenIDConnectProviders", "iam:ListRoles"], Resource = "*" },
      # GetOpenIDConnectProvider accepts the provider ARN (oidc-provider resource type), so it names only the GitHub
      # provider instead of "*".
      { Effect = "Allow", Action = ["iam:GetOpenIDConnectProvider"], Resource = data.aws_iam_openid_connect_provider.github.arn }
    ]
  })
}
