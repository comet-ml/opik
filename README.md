# opik

## Running Comet Opik locally

Comet Opik contains two main services:
1. Frontend available at `apps/opik-frontend/README.md`
2. Backend available at `apps/opik-backend/README.md`

### Python SDK

You can install the latest version of the Python SDK by running:

```bash
# Navigate and pull the latest changes if there are any
cd sdks/python
git checkout main
git pull

# Pip install the local version of the SDK
pip install -e . -U
```

## Running the full application locally with minikube

### Installation Prerequisites

- Docker - https://docs.docker.com/engine/install/

- kubectl - https://kubernetes.io/docs/tasks/tools/#kubectl

- Helm - https://helm.sh/docs/intro/install/

- minikube - https://minikube.sigs.k8s.io/docs/start

- more tools:
    - **`bash`** completion / `zsh` completion
    - `kubectx` and `kubens` - easy switch context/namespaces for kubectl -  https://github.com/ahmetb/kubectx

### Run k8s cluster locally

Start your `minikube` cluster https://minikube.sigs.k8s.io/docs/start/

```bash
minikube start
```

### Build and run 
Run the script that builds and runs Opik on `minikube`
```bash
./build_and_run.sh
```

Script options
```
--no-build          Skip the build process
--no-fe-build       Skip the FE build process
--no-helm-update    Skip helm repo update
--local-fe          Run FE locally (For frontend developers)
--help              Display help message
```
Note that when you run it for the first time it can take a few minutes to install everything

To check that your application is running enter url `http://localhost:5173`

To check api documentation enter url `http://localhost:3003`

You can run the `clickhouse-client` with
```bash
kubectl exec -it chi-opik-clickhouse-cluster-0-0-0 clickhouse-client
```
After the client is connected, you can check the databases with 
```bash
show databases;
```

### Some simple k8s commands to manage the installation
List the pods that are running
```bash
kubectl get pods
```
To restart a pod just delete the pod, k8s will start a new one
```bash
kubectl delete pod <pod name>
```
There is no clean way to delete the databases, so if you need to do that, it's better to delete the namespace and then install again.
Run 
```bash
kubectl delete namespace opik 
```
and in parallel (in another terminal window/tab) run 
```bash
kubectl patch chi opik-clickhouse --type json --patch='[ { "op": "remove", "path": "/metadata/finalizers" } ]'
```
after the namespace is deleted, run 
```bash
./build_and_run.sh --no-build
```
to install everything again

Stop minikube
```bash
minikube stop
```
Next time you will start the minikube, it will run everything with the same configuration and data you had before.


## Repository structure

`apps`

Contains the applications.

`apps/opik-backend`

Contains the Opik application. 

See `apps/opik-backend/README.md`.
