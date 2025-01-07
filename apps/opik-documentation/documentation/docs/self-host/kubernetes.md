---
sidebar_label: Production (Kubernetes)
description: Describes how to run Opik on a Kubernetes cluster
test_code_snippets: false
---

# Production ready Kubernetes deployment

For production deployments, we recommend using our Kubernetes Helm chart. This chart is designed to be highly configurable and has been battle-tested in Comet's managed cloud offering.

## Prerequisites

In order to install Opik on a Kubernetes cluster, you will need to have the following tools installed:

- [Docker](https://www.docker.com/)
- [Helm](https://helm.sh/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [kubectx](https://github.com/ahmetb/kubectx) and [kubens](https://github.com/ahmetb/kubectx) to switch between Kubernetes clusters and namespaces.

## Installation

You can install Opik using the helm chart maintained by the Opik team by running the following commands:

```bash
# Add Opik Helm repo
helm repo add opik https://comet-ml.github.io/opik/
helm repo update

# Install Opik
VERSION=latest
helm upgrade --install opik -n opik --create-namespace opik/opik \
    --set component.backend.image.tag=$VERSION --set component.frontend.image.tag=$VERSION
```

You can port-forward any service you need to your local machine:

```bash
kubectl port-forward -n opik svc/opik-frontend 5173
```

Opik will be available at `http://localhost:5173`.

## Configuration

You can find a full list the configuration options in the [helm chart documentation](https://github.com/comet-ml/opik/tree/main/deployment/helm_chart/opik).
