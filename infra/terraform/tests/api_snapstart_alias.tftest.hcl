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

  variables {
    deploy_compute         = true
    lambda_artifact_bucket = "caselens-test-artifacts"
    api_live_version       = "42"
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
}
