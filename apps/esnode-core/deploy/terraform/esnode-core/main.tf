terraform {
  required_version = ">= 1.3.0"
  required_providers {
    helm = {
      source  = "hashicorp/helm"
      version = ">= 2.8.0"
    }
  }
}

variable "namespace" {
  type        = string
  default     = "default"
  description = "Namespace to deploy esnode-core"
}

variable "release_name" {
  type        = string
  default     = "esnode-core"
  description = "Helm release name"
}

variable "chart_path" {
  type        = string
  default     = "../../helm/esnode-core"
  description = "Path to the local Helm chart"
}

variable "image_repository" {
  type        = string
  default     = "your-dockerhub-username/esnode-core"
  description = "Container image repository"
}

variable "image_tag" {
  type        = string
  default     = "0.1.0"
  description = "Container image tag"
}

variable "host_network" {
  type        = bool
  default     = true
  description = "Enable hostNetwork"
}

variable "host_pid" {
  type        = bool
  default     = true
  description = "Enable hostPID"
}

variable "privileged" {
  type        = bool
  default     = true
  description = "Run privileged for NVML access"
}

variable "tsdb_host_path" {
  type        = string
  default     = "/var/lib/esnode/tsdb"
  description = "Host path for TSDB storage"
}

variable "orchestrator_token" {
  type        = string
  default     = ""
  description = "Bearer token for orchestrator control API (empty to disable auth)"
  sensitive   = true
}

variable "orchestrator_allow_public" {
  type        = bool
  default     = false
  description = "Allow orchestrator control API on non-loopback"
}

provider "helm" {
  kubernetes {
    config_path = "~/.kube/config"
  }
}

resource "helm_release" "esnode_core" {
  name       = var.release_name
  namespace  = var.namespace
  chart      = var.chart_path

  set {
    name  = "image.repository"
    value = var.image_repository
  }
  set {
    name  = "image.tag"
    value = var.image_tag
  }
  set {
    name  = "hostNetwork"
    value = var.host_network
  }
  set {
    name  = "hostPID"
    value = var.host_pid
  }
  set {
    name  = "privileged"
    value = var.privileged
  }
  set {
    name  = "tsdb.hostPath"
    value = var.tsdb_host_path
  }
  set {
    name  = "tsdb.mountPath"
    value = var.tsdb_host_path
  }
  set {
    name  = "config.orchestrator.token"
    value = var.orchestrator_token
  }
  set {
    name  = "config.orchestrator.allowPublic"
    value = var.orchestrator_allow_public
  }
}
