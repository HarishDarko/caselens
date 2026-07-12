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
          "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:ref:refs/heads/${var.github_branch}"
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
      { Effect = "Allow", Action = ["s3:*"], Resource = ["arn:aws:s3:::${local.application_name}", "arn:aws:s3:::${local.application_name}/*", "arn:aws:s3:::${local.application_name}-artifacts", "arn:aws:s3:::${local.application_name}-artifacts/*"] },
      { Effect = "Allow", Action = ["sqs:*"], Resource = "arn:aws:sqs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:${local.application_name}-*" },
      { Effect = "Allow", Action = ["lambda:*"], Resource = ["arn:aws:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:function:${local.application_name}-*", "arn:aws:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:event-source-mapping:*"] },
      { Effect = "Allow", Action = ["logs:*"], Resource = "arn:aws:logs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:log-group:/aws/lambda/${local.application_name}-*" },
      { Effect = "Allow", Action = ["secretsmanager:*"], Resource = "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${local.application_name}/*" },
      { Effect = "Allow", Action = ["events:*"], Resource = "arn:aws:events:${var.aws_region}:${data.aws_caller_identity.current.account_id}:rule/${local.application_name}-*" },
      { Effect = "Allow", Action = ["cloudwatch:*"], Resource = "arn:aws:cloudwatch:${var.aws_region}:${data.aws_caller_identity.current.account_id}:alarm:${local.application_name}-*" },
      { Effect = "Allow", Action = ["apigateway:*", "cloudfront:*"], Resource = "*" },
      { Effect = "Allow", Action = ["iam:*"], Resource = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.application_name}-*" },
      { Effect = "Allow", Action = ["iam:ListRoles", "iam:GetOpenIDConnectProvider"], Resource = "*" }
    ]
  })
}
