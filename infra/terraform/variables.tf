variable "project_name" {
  type    = string
  default = "caselens"
}

variable "environment" {
  type    = string
  default = "demo"
}

variable "aws_region" {
  type    = string
  default = "ca-central-1"
}

variable "github_repository" {
  description = "Owner/repository allowed to assume the deployment role."
  type        = string
  default     = "HarishDarko/caselens"
}

variable "github_branch" {
  type    = string
  default = "feat/production-proof"
}

variable "domain_aliases" {
  type    = list(string)
  default = []
}

variable "acm_certificate_arn" {
  type    = string
  default = null

  validation {
    condition     = length(var.domain_aliases) == 0 || var.acm_certificate_arn != null
    error_message = "acm_certificate_arn is required when domain_aliases is not empty."
  }
}

variable "web_origins" {
  description = "Browser origins allowed to call the API, including the CloudFront URL or custom domain."
  type        = list(string)
  default     = []
}

variable "deploy_compute" {
  description = "Enable Lambda/API Gateway resources only after artifacts and database connectivity are prepared."
  type        = bool
  default     = false
}

variable "lambda_artifact_bucket" {
  type    = string
  default = ""

  validation {
    condition     = !var.deploy_compute || length(trimspace(var.lambda_artifact_bucket)) > 0
    error_message = "lambda_artifact_bucket is required when deploy_compute is true."
  }
}

variable "api_lambda_s3_key" {
  type    = string
  default = "artifacts/caselens-lambda.jar"
}

variable "worker_lambda_s3_key" {
  type    = string
  default = "artifacts/caselens-lambda.jar"
}

variable "relay_lambda_s3_key" {
  type    = string
  default = "artifacts/caselens-lambda.jar"
}

variable "alarm_topic_arns" {
  type    = list(string)
  default = []
}
