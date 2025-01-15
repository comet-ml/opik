---
sidebar_label: Local (Docker Compose)
description: Describes how to run Opik locally using Docker Compose
pytest_codeblocks_skip: true
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

Opik will now be available at <a href="http://localhost:5173" target="_blank">http://localhost:5173</a>

:::tip
In order to use the Opik Python SDK with your local Opik instance, you will need to run:

```bash
pip install opik

opik configure --use_local
```

or in python:

```python
import opik

opik.configure(use_local=True)
```

This will create a `~/.opik.config` file that will store the URL of your local Opik instance.
:::

All the data logged to the Opik platform will be stored in the `~/opik` directory, which means that you can start and stop the Opik platform without losing any data.

## Starting, stopping

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

**Note:** You can safely start and stop the Opik platform without losing any data.

## Upgrading Opik

To upgrade Opik, you can run the following command:

```bash
# Navigate to the opik/deployment/docker-compose directory
cd opik/deployment/docker-compose

# Update the repository to pull the most recent docker compose file
git pull

# Update the docker compose image to get the most recent version of Opik
docker compose pull

# Restart the Opik platform with the latest changes
docker compose up --detach
```

:::tip
Since the Docker Compose deployment is using mounted volumes, your data will **_not_** be lost when you upgrade Opik. You can also safely start and stop the Opik platform without losing any data.
:::

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

# Navigate to the opik/deployment/docker-compose directory
cd opik/deployment/docker-compose

# Build the Opik platform from source
docker compose up --build
```

This will build the Frontend and Backend Docker images and start the Opik platform.
