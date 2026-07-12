variable "aws_region" {
  type    = string
  default = "ca-central-1"
}

variable "bucket_name" {
  description = "Globally unique private S3 bucket used for CaseLens Terraform state."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name must be a valid globally unique S3 bucket name."
  }
}


variable "project_name" {
  type    = string
  default = "caselens"
}

variable "environment" {
  type    = string
  default = "demo"
}

variable "github_repository" {
  type    = string
  default = "HarishDarko/caselens"
}

variable "github_branch" {
  type    = string
  default = "feat/production-proof"
}
