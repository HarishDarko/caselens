terraform {
  backend "s3" {
    key          = "caselens/demo/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
