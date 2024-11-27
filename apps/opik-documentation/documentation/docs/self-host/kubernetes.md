---
sidebar_position: 1
sidebar_label: Production (Kubernetes)
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

You will then be able to to install Opik using the helm chart defined in the `deployment/helm_chart/opik` directory of the [Opik repository](https://github.com/comet-ml/opik):

```bash
# Navigate to the directory
cd deployment/helm_chart/opik

# Define the version of the Opik server you want to install
VERSION=latest

# Add helm dependencies
helm repo add bitnami https://charts.bitnami.com/bitnami
helm dependency build

# Add Opik Helm repo
helm repo add opik https://comet-ml.github.io/opik/
helm repo update

# Install Opik
VERSION=0.1.0
helm upgrade --install opik -n opik --create-namespace opik/opik \
    --set component.backend.image.tag=$VERSION --set component.frontend.image.tag=$VERSION
```

To access the Opik UI, you will need to port-forward the frontend service:

```bash
kubectl port-forward -n llm svc/opik-frontend 5173
```

You can now open the Opik UI at `http://localhost:5173/llm`.

## Configuration

You can find a full list the configuration options in the [helm chart documentation](https://github.com/comet-ml/opik/tree/main/deployment/helm_chart/opik).
