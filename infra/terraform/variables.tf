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

variable "bootstrap_live_aliases" {
  description = "One-time initial deployment mode that derives live aliases from versions created in the same apply."
  type        = bool
  default     = false
}

variable "api_live_version" {
  description = "Published, SnapStart-optimized API version approved for live traffic; omit only during initial bootstrap."
  type        = string
  default     = null

  validation {
    condition = (!var.deploy_compute || var.bootstrap_live_aliases
    || (var.api_live_version != null && can(regex("^[1-9][0-9]*$", var.api_live_version))))
    error_message = "api_live_version must be a positive published version unless bootstrap_live_aliases is explicitly enabled."
  }
}

variable "worker_live_version" {
  description = "Published, SnapStart-optimized worker version approved for live traffic; omit only during initial bootstrap."
  type        = string
  default     = null

  validation {
    condition = (!var.deploy_compute || var.bootstrap_live_aliases
    || (var.worker_live_version != null && can(regex("^[1-9][0-9]*$", var.worker_live_version))))
    error_message = "worker_live_version must be a positive published version unless bootstrap_live_aliases is explicitly enabled."
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

variable "force_destroy_storage" {
  description = "Allow the explicit teardown workflow to empty versioned application buckets before deletion."
  type        = bool
  default     = false
}
