# esnode-core Helm module (Terraform)

This module wraps the local Helm chart at `deploy/helm/esnode-core` to deploy ESNODE-Core as a DaemonSet.

## Usage
```hcl
module "esnode_core" {
  source = "./deploy/terraform/esnode-core"

  namespace                 = "default"
  release_name              = "esnode-core"
  image_repository          = "myregistry/esnode-core"
  image_tag                 = "0.1.0"
  tsdb_host_path            = "/var/lib/esnode/tsdb"
  orchestrator_allow_public = false
  orchestrator_token        = ""
}
```

Then:
```bash
terraform init
terraform apply
```

## Preparing for Terraform Registry
- Recommended repository name: `terraform-helm-esnode-core` for Registry autodiscovery.
- Tag releases (e.g., `v0.1.0`) and include this module path or mirror it into a dedicated repo for publishing to the Terraform Registry (`esnode/esnode-core/helm` style).

## Inputs
- `namespace` (string): target namespace. Default: `default`.
- `release_name` (string): Helm release name. Default: `esnode-core`.
- `image_repository` (string): container image repository. Default: `your-dockerhub-username/esnode-core`.
- `image_tag` (string): container image tag. Default: `0.1.0`.
- `host_network` (bool): enable hostNetwork. Default: `true`.
- `host_pid` (bool): enable hostPID. Default: `true`.
- `privileged` (bool): run privileged for NVML access. Default: `true`.
- `tsdb_host_path` (string): host path for TSDB storage. Default: `/var/lib/esnode/tsdb`.
- `orchestrator_token` (string): bearer token for control API. Default: empty.
- `orchestrator_allow_public` (bool): allow non-loopback control API. Default: `false`.

## Outputs
- `release_name`: Helm release name.
- `namespace`: deployed namespace.
- `service_name`: service name for metrics scraping.
