locals {
  name = "${var.project_name}-${var.environment}"
}

resource "aws_s3_bucket" "frontend" {
  bucket        = local.name
  force_destroy = var.force_destroy_storage

}

resource "aws_s3_bucket_versioning" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket" "artifacts" {
  bucket        = "${local.name}-artifacts"
  force_destroy = var.force_destroy_storage

}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = local.name
  description                       = "CloudFront access to the private CaseLens frontend bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"

}

resource "aws_cloudfront_response_headers_policy" "frontend_security" {
  name = "${local.name}-security-headers"


  security_headers_config {
    content_security_policy {
      content_security_policy = "default-src 'self'; connect-src 'self' https:; img-src 'self' data:; style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
      override                = true
    }
    content_type_options { override = true }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "no-referrer"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = false
      override                   = true
    }
  }
}

resource "aws_cloudfront_distribution" "frontend" {
  enabled             = true
  default_root_object = "index.html"
  aliases             = var.domain_aliases

  origin {
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_id                = aws_s3_bucket.frontend.id
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  default_cache_behavior {
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    target_origin_id           = aws_s3_bucket.frontend.id
    viewer_protocol_policy     = "redirect-to-https"
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.frontend_security.id
    forwarded_values {
      query_string = true
      cookies { forward = "none" }
    }
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }

  viewer_certificate {
    cloudfront_default_certificate = var.acm_certificate_arn == null
    acm_certificate_arn            = var.acm_certificate_arn
    ssl_support_method             = var.acm_certificate_arn == null ? null : "sni-only"
    minimum_protocol_version       = var.acm_certificate_arn == null ? "TLSv1" : "TLSv1.2_2021"
  }

  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/index.html"
  }
}

resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowCloudFrontRead"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.frontend.arn}/*"
      Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.frontend.arn } }
    }]
  })
}

resource "aws_sqs_queue" "dlq" {
  name                      = "${local.name}-triage-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true

}

resource "aws_sqs_queue" "triage" {
  name                       = "${local.name}-triage"
  visibility_timeout_seconds = 90
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 5
  })
}

resource "aws_secretsmanager_secret" "application" {
  name                    = "${local.name}/application"
  description             = "Runtime secrets are provisioned out-of-band; Terraform never stores their values."
  recovery_window_in_days = 7

}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/aws/lambda/${local.name}-api"
  retention_in_days = 30

}

resource "aws_cloudwatch_log_group" "worker" {
  name              = "/aws/lambda/${local.name}-worker"
  retention_in_days = 30

}

resource "aws_cloudwatch_log_group" "relay" {
  name              = "/aws/lambda/${local.name}-relay"
  retention_in_days = 30

}

data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

data "aws_caller_identity" "current" {}

resource "aws_iam_role" "github_deploy" {
  name = "${local.name}-github-deploy"
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

resource "aws_iam_role_policy" "github_deploy" {
  role = aws_iam_role.github_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = [aws_s3_bucket.frontend.arn, aws_s3_bucket.artifacts.arn] },
      { Effect = "Allow", Action = ["s3:PutObject", "s3:DeleteObject", "s3:GetObject"], Resource = "${aws_s3_bucket.frontend.arn}/*" },
      { Effect = "Allow", Action = ["s3:PutObject", "s3:DeleteObject", "s3:GetObject"], Resource = "${aws_s3_bucket.artifacts.arn}/*" },
      { Effect = "Allow", Action = ["lambda:GetFunction", "lambda:UpdateFunctionCode", "lambda:PublishVersion"], Resource = "arn:aws:lambda:${var.aws_region}:${data.aws_caller_identity.current.account_id}:function:${local.name}-*" },
      { Effect = "Allow", Action = ["cloudfront:CreateInvalidation"], Resource = aws_cloudfront_distribution.frontend.arn }
    ]
  })
}

resource "aws_iam_role" "lambda_runtime" {
  name = "${local.name}-lambda-runtime"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })

}

resource "aws_iam_role_policy_attachment" "lambda_logs" {
  role       = aws_iam_role.lambda_runtime.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "lambda_queue" {
  role = aws_iam_role.lambda_runtime.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "sqs:SendMessage"], Resource = [aws_sqs_queue.triage.arn, aws_sqs_queue.dlq.arn] }]
  })
}

resource "aws_iam_role_policy" "lambda_secrets" {
  role = aws_iam_role.lambda_runtime.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_secretsmanager_secret.application.arn }]
  })
}

resource "aws_lambda_function" "api" {
  count            = var.deploy_compute ? 1 : 0
  function_name    = "${local.name}-api"
  role             = aws_iam_role.lambda_runtime.arn
  runtime          = "java21"
  handler          = "com.harishdarko.caselens.lambda.ApiHandler"
  s3_bucket        = var.lambda_artifact_bucket
  s3_key           = var.api_lambda_s3_key
  publish          = true
  timeout          = 30
  memory_size      = 1536
  source_code_hash = null
  snap_start { apply_on = "PublishedVersions" }
  environment {
    variables = merge({
      CASELENS_TRIAGE_QUEUE_URL               = aws_sqs_queue.triage.url
      CASELENS_SECRETS_MANAGER_NAME           = aws_secretsmanager_secret.application.name
      CASELENS_QUEUE_ENABLED                  = "true"
      CASELENS_RUNTIME_ENVIRONMENT            = "aws-demo"
      CASELENS_RUNTIME_DATABASE               = "neon-postgresql"
      CASELENS_RUNTIME_QUEUE                  = "amazon-sqs"
      CASELENS_DEMO_FAILURE_SCENARIOS_ENABLED = "true"
      MANAGEMENT_HEALTH_DISKSPACE_ENABLED     = "false"
      SPRING_PROFILES_ACTIVE                  = "lambda"
    }, length(var.web_origins) > 0 ? { CASELENS_WEB_ORIGINS = join(",", var.web_origins) } : {})
  }
}

resource "aws_lambda_alias" "api" {
  count            = var.deploy_compute ? 1 : 0
  name             = "live"
  description      = "SnapStart-optimized API version used by API Gateway"
  function_name    = aws_lambda_function.api[0].function_name
  function_version = var.api_live_version
}

resource "aws_lambda_function" "worker" {
  count         = var.deploy_compute ? 1 : 0
  function_name = "${local.name}-worker"
  role          = aws_iam_role.lambda_runtime.arn
  runtime       = "java21"
  handler       = "com.harishdarko.caselens.lambda.WorkerHandler"
  s3_bucket     = var.lambda_artifact_bucket
  s3_key        = var.worker_lambda_s3_key
  publish       = true
  timeout       = 60
  memory_size   = 1024
  snap_start { apply_on = "PublishedVersions" }
  environment {
    variables = merge({
      CASELENS_TRIAGE_QUEUE_URL               = aws_sqs_queue.triage.url
      CASELENS_SECRETS_MANAGER_NAME           = aws_secretsmanager_secret.application.name
      CASELENS_QUEUE_ENABLED                  = "true"
      CASELENS_RUNTIME_ENVIRONMENT            = "aws-demo"
      CASELENS_RUNTIME_DATABASE               = "neon-postgresql"
      CASELENS_RUNTIME_QUEUE                  = "amazon-sqs"
      CASELENS_DEMO_FAILURE_SCENARIOS_ENABLED = "true"
      MANAGEMENT_HEALTH_DISKSPACE_ENABLED     = "false"
      SPRING_PROFILES_ACTIVE                  = "lambda"
    }, length(var.web_origins) > 0 ? { CASELENS_WEB_ORIGINS = join(",", var.web_origins) } : {})
  }
}

resource "aws_lambda_function" "relay" {
  count         = var.deploy_compute ? 1 : 0
  function_name = "${local.name}-relay"
  role          = aws_iam_role.lambda_runtime.arn
  runtime       = "java21"
  handler       = "com.harishdarko.caselens.lambda.RelayHandler"
  s3_bucket     = var.lambda_artifact_bucket
  s3_key        = var.relay_lambda_s3_key
  publish       = true
  timeout       = 30
  memory_size   = 1024
  snap_start { apply_on = "PublishedVersions" }
  environment {
    variables = merge({
      CASELENS_TRIAGE_QUEUE_URL               = aws_sqs_queue.triage.url
      CASELENS_SECRETS_MANAGER_NAME           = aws_secretsmanager_secret.application.name
      CASELENS_QUEUE_ENABLED                  = "true"
      CASELENS_RUNTIME_ENVIRONMENT            = "aws-demo"
      CASELENS_RUNTIME_DATABASE               = "neon-postgresql"
      CASELENS_RUNTIME_QUEUE                  = "amazon-sqs"
      CASELENS_DEMO_FAILURE_SCENARIOS_ENABLED = "true"
      MANAGEMENT_HEALTH_DISKSPACE_ENABLED     = "false"
      SPRING_PROFILES_ACTIVE                  = "lambda"
    }, length(var.web_origins) > 0 ? { CASELENS_WEB_ORIGINS = join(",", var.web_origins) } : {})
  }
}

resource "aws_apigatewayv2_api" "api" {
  count         = var.deploy_compute ? 1 : 0
  name          = "${local.name}-api"
  protocol_type = "HTTP"

}

resource "aws_apigatewayv2_integration" "api" {
  count                  = var.deploy_compute ? 1 : 0
  api_id                 = aws_apigatewayv2_api.api[0].id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_alias.api[0].invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "default" {
  count     = var.deploy_compute ? 1 : 0
  api_id    = aws_apigatewayv2_api.api[0].id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.api[0].id}"
}

resource "aws_apigatewayv2_stage" "default" {
  count       = var.deploy_compute ? 1 : 0
  api_id      = aws_apigatewayv2_api.api[0].id
  name        = "$default"
  auto_deploy = true
  route_settings {
    route_key              = "$default"
    throttling_burst_limit = 10
    throttling_rate_limit  = 5
  }
}

resource "aws_lambda_permission" "api_gateway" {
  count         = var.deploy_compute ? 1 : 0
  statement_id  = "AllowHttpApiInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api[0].function_name
  qualifier     = aws_lambda_alias.api[0].name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.api[0].execution_arn}/*/*"
}

resource "aws_lambda_event_source_mapping" "worker" {
  count                   = var.deploy_compute ? 1 : 0
  event_source_arn        = aws_sqs_queue.triage.arn
  function_name           = aws_lambda_function.worker[0].arn
  batch_size              = 1
  function_response_types = ["ReportBatchItemFailures"]
}

resource "aws_cloudwatch_event_rule" "relay_schedule" {
  count               = var.deploy_compute ? 1 : 0
  name                = "${local.name}-relay-schedule"
  schedule_expression = "rate(1 minute)"

}

resource "aws_cloudwatch_event_target" "relay" {
  count     = var.deploy_compute ? 1 : 0
  rule      = aws_cloudwatch_event_rule.relay_schedule[0].name
  target_id = "relay"
  arn       = aws_lambda_function.relay[0].arn
}

resource "aws_lambda_permission" "relay_schedule" {
  count         = var.deploy_compute ? 1 : 0
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.relay[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.relay_schedule[0].arn
}

resource "aws_cloudwatch_metric_alarm" "queue_depth" {
  alarm_name          = "${local.name}-queue-depth"
  alarm_description   = "Triage work is waiting beyond the normal demo baseline."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.triage.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 10
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arns
}

resource "aws_cloudwatch_metric_alarm" "dlq_depth" {
  alarm_name          = "${local.name}-dlq-depth"
  alarm_description   = "Messages reached the triage dead-letter queue."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dlq.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arns
}

resource "aws_cloudwatch_metric_alarm" "api_errors" {
  count               = var.deploy_compute ? 1 : 0
  alarm_name          = "${local.name}-api-errors"
  alarm_description   = "The API Lambda is returning or recording errors."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.api[0].function_name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 3
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arns
}
