mock_provider "aws" {}

# The CloudFront edge: the page may talk only to itself and the API, a missing file is a real 404 instead of the
# page (which a browser refuses as a script, leaving a blank screen), and API access is logged.
# Run with: terraform init -backend=false && terraform test -filter=tests/edge.tftest.hcl
run "edge_serves_real_404s_and_a_narrow_csp" {
  command = plan

  override_resource {
    target          = aws_apigatewayv2_api.api
    override_during = plan
    values = {
      api_endpoint  = "https://abc123.execute-api.ca-central-1.amazonaws.com"
      execution_arn = "arn:aws:execute-api:ca-central-1:123456789012:abc123"
    }
  }

  override_resource {
    target          = aws_cloudfront_distribution.frontend
    override_during = plan
    values = {
      arn = "arn:aws:cloudfront::123456789012:distribution/E1TEST"
    }
  }

  override_resource {
    target          = aws_s3_bucket.frontend
    override_during = plan
    values = {
      arn = "arn:aws:s3:::caselens-test"
    }
  }

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    api_live_version       = "42"
    worker_live_version    = "43"
    relay_live_version     = "44"
  }

  assert {
    condition     = strcontains(aws_cloudfront_response_headers_policy.frontend_security.security_headers_config[0].content_security_policy[0].content_security_policy, "connect-src 'self' https://abc123.execute-api.ca-central-1.amazonaws.com;")
    error_message = "connect-src must name only the page itself and the API origin, not every https host."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.frontend.custom_error_response) == 0
    error_message = "A missing file must stay a 404; serving index.html with status 200 leaves old pages blank after a deploy."
  }

  assert {
    condition     = length(aws_cloudfront_distribution.frontend.default_cache_behavior[0].forwarded_values) == 0
    error_message = "The default behavior must use a cache policy (Brotli, no query string in the key), not legacy forwarded_values."
  }

  assert {
    condition     = strcontains(aws_s3_bucket_policy.frontend.policy, "s3:ListBucket")
    error_message = "CloudFront needs s3:ListBucket so S3 answers 404, not 403, for a missing file."
  }

  assert {
    condition     = aws_cloudwatch_log_group.api_access[0].retention_in_days == 7
    error_message = "API access logs are kept for 7 days."
  }
}
