---
sidebar_position: 1
sidebar_label: Overview
---

# Self-host

You can use Opik through [Comet's Managed Cloud offering](https://comet.com/site) or you can self-host Opik on your own infrastructure. When choosing to self-host Opik, you get access to all Opik features including tracing, evaluation, etc but without user management features.

If you choose to self-host Opik, you can choose between two deployment options:

1. All-in-one installation: The Opik platform runs on a single server.
2. Kubernetes installation: The Opik platform runs on a Kubernetes cluster.

If you are looking at just getting started, we recommend the all-in-one installation. For more advanced use cases, you can choose the Kubernetes installation.

## All-in-one installation

The all-in-one installer is the easiest way to get started with Opik.

### Installation

To install the Opik server, run the following command:

```bash
opik-server install
```

You can also run the installer in debug mode to see the details of the
installation process:

```bash
opik-server --debug install
```

:::tip
We recommend installing using the `--debug` flag as the installation can take a couple of minutes
:::

The opik installer has been tested on the following operating systems:

- Ubuntu 22.04
- MacOS

By default, the installer will install the same version of the Opik as its
own version (`opik-server -v`). If you want to install a specific version, you
can specify the version using the `--opik-version` flag:

```bash
opik-server install --opik-version 0.1.0
```

By default, the installer will setup a local port forward to the Opik server
using the port `5173`. If you want to use a different port, you can specify
the port using the `--local-port` flag:

```bash
opik-server install --local-port 5174
```

The installation process takes a couple of minutes and when complete, Opik will be available at `http://localhost:5173`.

### Upgrading the Opik server

To upgrade the Opik server, run the following command:

```bash
pip install --upgrade opik-server
opik-server upgrade
```

Or upgrade to a specific version:

```bash
opik-server upgrade --opik-version 0.1.1
```

### Uninstalling the Opik server

To uninstall the Opik server, you can run the following command:

```bash
minikube delete
```

## Kubernetes installation

If you are looking for a more customization options, you can choose to install Opik on a Kubernetes cluster.

In order to install Opik on a Kubernetes cluster, you will need to have the following tools installed:

- [Docker](https://www.docker.com/)
- [Helm](https://helm.sh/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [kubectx](https://github.com/ahmetb/kubectx) and [kubens](https://github.com/ahmetb/kubectx) to switch between Kubernetes clusters and namespaces.

To install Opik, you can use the helm chart defined in the `deployment/helm_chart/opik` directory of the [Opik repository](https://github.com/comet-ml/opik):

```bash
# Navigate to the directory
cd deployment/helm_chart/opik

# Define the version of the Opik server you want to install
VERSION=main

# Add helm dependencies
helm repo add bitnami https://charts.bitnami.com/bitnami
helm dependency build

# Install Opik
helm upgrade --install opik -n llm --create-namespace -f values.yaml \
    --set registry=docker.dev.comet.com/comet-ml \
    --set component.backend.image.tag=$VERSION --set component.frontend.image.tag=$VERSION-os \
    --set component.backend.env.ANALYTICS_DB_MIGRATIONS_PASS=opik --set component.backend.env.ANALYTICS_DB_PASS=opik \
    --set component.backend.env.STATE_DB_PASS=opik .
```

To access the Opik UI, you will need to port-forward the frontend service:

```bash
kubectl port-forward -n llm svc/opik-frontend 5173
```

You can now open the Opik UI at `http://localhost:5173/llm`.

### Configuration

You can find a full list the configuration options in the [helm chart documentation](https://github.com/comet-ml/opik/tree/main/deployment/helm_chart/opik).
