terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      version = "5.57"
    }
  }
  backend "s3" {
    bucket = "s3bucket-project-devops"
    key    = "expense-dev-eks"
    region = "us-east-1"
    use_lockfile = true
  }
}

#provide authentication here
provider "aws" {
  region = "us-east-1"
}