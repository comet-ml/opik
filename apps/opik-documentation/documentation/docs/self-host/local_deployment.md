---
sidebar_position: 1
sidebar_label: Local (Docker Compose)
---

# Local Deployments using Docker Compose

To run Opik locally we recommend using [Docker Compose](https://docs.docker.com/compose/). It's easy to setup and allows you to get started in a couple of minutes **but** is not meant for production deployments. If you would like to run Opik in a production environment, we recommend using our [Kubernetes Helm chart](./kubernetes.md).

Before running the installation, make sure you have Docker and Docker Compose installed:

- [Docker](https://docs.docker.com/get-docker/)
- [Docker Compose](https://docs.docker.com/compose/install/)

:::note
If you are using Mac or Windows, both `docker` and `docker compose` are included in the [Docker Desktop](https://docs.docker.com/desktop/) installation.
:::

## Installation

To install Opik, you will need to clone the Opik repository and run the `docker-compose.yaml` file:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the opik/deployment/docker-compose directory
cd opik/deployment/docker-compose

# Start the Opik platform
docker compose up --detach
```

Opik will now be available at `http://localhost:5173`.

:::tip
You will need to make sure that the Opik Python SDK is configured to point to the Opik server you just started. For this, make sure you set the environment variable `OPIK_BASE_URL` to the URL of the Opik server:

```bash
export OPIK_BASE_URL=http://localhost:5173/api
```

or in python:

```python
import os

os.environ["OPIK_BASE_URL"] = "http://localhost:5173/api"
```
:::

All the data logged to the Opik platform will be stored in the `~/opik` directory, which means that you can start and stop the Opik platform without losing any data.

## Starting, stopping and upgrading Opik

:::note
All the `docker compose` commands should be run from the `opik/deployment/docker-compose` directory.
:::

The `docker compose up` command can be used to install, start and upgrade Opik:

```bash
# Start, upgrade or restart the Opik platform
docker compose up --detach
```

To stop Opik, you can run:

```bash
# Stop the Opik platform
docker compose down
```

## Removing Opik

To remove Opik, you will need to remove the Opik containers and volumes:

```bash
# Remove the Opik containers and volumes
docker compose down --volumes
```

:::warning
Removing the volumes will delete all the data stored in the Opik platform and cannot be recovered. We do not recommend this option unless you are sure that you will not need any of the data stored in the Opik platform.
:::

## Advanced configuration

### Running a specific version of Opik

You can run a specific version of Opik by setting the `OPIK_VERSION` environment variable:

```bash
OPIK_VERSION=latest docker compose up
```

### Building the Opik platform from source

You can also build the Opik platform from source by running the following command:

```bash
# Clone the Opik repository
git clone https://github.com/comet-ml/opik.git

# Navigate to the opik directory
cd opik

# Build the Opik platform from source
docker compose up --build
```

This will build the Frontend and Backend Docker images and start the Opik platform.
