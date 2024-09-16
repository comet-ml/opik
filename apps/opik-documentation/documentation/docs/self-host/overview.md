---
sidebar_position: 1
sidebar_label: Overview
---

# Self-hosting Opik

You can use Opik through [Comet's Managed Cloud offering](https://comet.com/site) or you can self-host Opik on your own infrastructure. When choosing to self-host Opik, you get access to all Opik features including tracing, evaluation, etc but without user management features.

If you choose to self-host Opik, you can choose between two deployment options:

1. [Local installation](./local_deployment.md): Perfect to get started but not production-ready.
2. [Kubernetes installation](./kubernetes.md): Production ready Opik platform that runs on a Kubernetes cluster.

## Getting started

If you would like to try out Opik locally, we recommend using our Local installation based on `docker compose`. Assuming you have `git` and `docker` installed, you can get started in a couple of minutes:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Run the Opik platform
cd opik/deployment/docker-compose
docker compose up --detach

# Configure the Python SDK to point to the local Opik platform
pip install opik
opik login --local
```

Opik will now be available at `http://localhost:5173` and all traces logged from your local machine will be logged to this local Opik instance.

To learn more about how to manage you local Opik deployment, you can refer to our [local deployment guide](./local_deployment.md).

## Advanced deployment options

If you would like to deploy Opik on a Kubernetes cluster, we recommend following our Kubernetes deployment guide [here](./kubernetes.md).

## Comet managed deployments

The Opik platform is being developed and maintained by the Comet team. If you are looking for a managed deployment solution, feel free to reach out to the Comet team at sales@comet.com or visit the [Comet website](https://comet.com/site) to learn more.
