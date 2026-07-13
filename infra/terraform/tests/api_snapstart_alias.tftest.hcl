mock_provider "aws" {}

run "api_gateway_uses_snapstart_alias" {
  command = plan

  override_resource {
    target          = aws_lambda_function.api
    override_during = plan
    values = {
      version = "42"
    }
  }

  override_resource {
    target          = aws_lambda_alias.api
    override_during = plan
    values = {
      invoke_arn = "arn:aws:apigateway:ca-central-1:lambda:path/2015-03-31/functions/arn:aws:lambda:ca-central-1:123456789012:function:caselens-test-api:live/invocations"
    }
  }

  override_resource {
    target          = aws_lambda_alias.worker
    override_during = plan
    values = {
      arn = "arn:aws:lambda:ca-central-1:123456789012:function:caselens-test-worker:live"
    }
  }

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    api_live_version       = "42"
    worker_live_version    = "43"
  }

  assert {
    condition     = aws_lambda_alias.api[0].name == "live"
    error_message = "The API Lambda must expose a stable live alias."
  }

  assert {
    condition     = aws_lambda_function.api[0].environment[0].variables["SPRING_PROFILES_ACTIVE"] == "lambda"
    error_message = "The API function must activate its Lambda-specific datasource lifecycle configuration."
  }

  assert {
    condition     = aws_lambda_alias.api[0].function_version == "42"
    error_message = "The live alias must target the explicitly approved published version."
  }

  assert {
    condition     = aws_apigatewayv2_integration.api[0].integration_uri == aws_lambda_alias.api[0].invoke_arn
    error_message = "API Gateway must invoke the SnapStart-enabled alias instead of $LATEST."
  }

  assert {
    condition     = aws_lambda_alias.worker[0].function_version == "43"
    error_message = "The worker alias must target the explicitly approved published version."
  }

  assert {
    condition     = aws_lambda_event_source_mapping.worker[0].function_name == aws_lambda_alias.worker[0].arn
    error_message = "SQS must invoke the SnapStart-enabled worker alias instead of $LATEST."
  }
}

run "initial_compute_deployment_bootstraps_alias_versions" {
  command = plan

  override_resource {
    target          = aws_lambda_function.api
    override_during = plan
    values = {
      version = "44"
    }
  }

  override_resource {
    target          = aws_lambda_function.worker
    override_during = plan
    values = {
      version = "45"
    }
  }

  override_resource {
    target          = aws_lambda_alias.api
    override_during = plan
    values = {
      invoke_arn = "arn:aws:apigateway:ca-central-1:lambda:path/2015-03-31/functions/arn:aws:lambda:ca-central-1:123456789012:function:caselens-test-api:live/invocations"
    }
  }

  override_resource {
    target          = aws_lambda_alias.worker
    override_during = plan
    values = {
      arn = "arn:aws:lambda:ca-central-1:123456789012:function:caselens-test-worker:live"
    }
  }

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    bootstrap_live_aliases = true
  }

  assert {
    condition     = aws_lambda_alias.api[0].function_version == aws_lambda_function.api[0].version
    error_message = "Initial deployment must bootstrap the API alias from Terraform's published version."
  }

  assert {
    condition     = aws_lambda_alias.worker[0].function_version == aws_lambda_function.worker[0].version
    error_message = "Initial deployment must bootstrap the worker alias from Terraform's published version."
  }
}

run "later_compute_deployment_requires_pinned_versions" {
  command = plan

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
  }

  expect_failures = [
    var.api_live_version,
    var.worker_live_version,
  ]
}
