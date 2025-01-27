#!/bin/bash
set -e

OPIK_BACKEND="opik-backend"
OPIK_FRONTEND="opik-frontend"
OPIK_CLICKHOUSE="clickhouse-opik-clickhouse"
OPIK_MYSQL="opik-mysql"
OPIK_REDIS="opik-redis"
OPIK_FRONTEND_PORT=5173
OPIK_BACKEND_PORT=8080
OPIK_OPENAPI_PORT=3003
OPIK_CLICKHOUSE_PORT=8123
OPIK_MYSQL_PORT=3306
OPIK_REDIS_PORT=6379
DOCKER_REGISTRY_LOCAL="local"
BUILD=true
FE_BUILD=true
HELM_UPDATE=true
LOCAL_FE=false
LOCAL_FE_PORT=${LOCAL_FE_PORT:-5174}
CLOUD_VERSION=false

function show_help() {
    echo "Usage: ./build_and_run.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --no-build          Skip the build process."
    echo "  --no-fe-build       Skip the FE build process."
    echo "  --no-helm-update    Skip helm repo update."
    echo "  --local-fe          Run FE locally."
    echo "  --cloud             Run it inside the /opik/ path."
    echo "  --help              Display this help message."
    echo ""
    echo "Example:"
    echo "  ./build_and_run.sh --no-build"
}

# Parse command line arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --no-build) BUILD=false ;;
        --no-fe-build) FE_BUILD=false ;;
        --no-helm-update) HELM_UPDATE=false ;;
        --local-fe) LOCAL_FE=true ;;
        --cloud) CLOUD_VERSION=true ;;
        --help) show_help; exit 0 ;;
        *) echo "Unknown parameter passed: $1"; show_help; exit 1 ;;
    esac
    shift
done

# Check if Minikube is running
if minikube status | grep -q "host: Running"; then
  echo "Minikube is running."
else
  echo "Minikube is not running, starting minikube.."
  minikube start
fi

# Switch kubectl context to Minikube
kubectl config use-context minikube

if $BUILD; then
    #### Building docker images
    eval $(minikube docker-env)
    echo "### Build docker images"

    # Create and use buildx builder for multi-arch builds
    echo "## Setting up Docker buildx for multi-arch builds"
    docker buildx create --name opik-builder --use || true
    docker buildx inspect --bootstrap

    echo "## Build Opik backend"
    cd apps/${OPIK_BACKEND}
    DOCKER_IMAGE_NAME=${DOCKER_REGISTRY_LOCAL}/${OPIK_BACKEND}:latest
    echo "DOCKER_IMAGE_NAME is ${DOCKER_IMAGE_NAME}"
    DOCKER_BUILDKIT=1 docker buildx build \
        --platform linux/amd64,linux/arm64 \
        --build-arg OPIK_VERSION=latest \
        --load \
        -t ${DOCKER_IMAGE_NAME} .
    cd -

    if $FE_BUILD; then
        echo "## Build Opik frontend"
        cd apps/${OPIK_FRONTEND}
        DOCKER_IMAGE_NAME=${DOCKER_REGISTRY_LOCAL}/${OPIK_FRONTEND}:latest
        echo "DOCKER_IMAGE_NAME is ${DOCKER_IMAGE_NAME}"
        DOCKER_FE_BUILD_ARGS=""
        if [[ "${CLOUD_VERSION}" == "true" ]]; then
          DOCKER_FE_BUILD_ARGS="--build-arg BUILD_MODE=comet"
        fi

        DOCKER_BUILDKIT=1 docker buildx build \
            --platform linux/amd64,linux/arm64 \
            --build-arg OPIK_VERSION=latest \
            ${DOCKER_FE_BUILD_ARGS} \
            --load \
            -t ${DOCKER_IMAGE_NAME} .
        cd -
    fi
fi

### Install/upgrade Opik on minikube
echo
echo "### Install Opik using latest versions"
cd deployment/helm_chart/opik
VERSION=latest

if [[ "${LOCAL_FE}" == "true" ]]; then
  LOCAL_FE_FLAGS="--set localFE=true"
  if [ -z "${LOCAL_FE_HOST}" ] ; then
    if [[ "$OSTYPE" == "darwin"* ]]; then
      LOCAL_FE_HOST=$(ifconfig | grep 'inet ' | grep -vF '127.0.0.1' | awk '{print $2}' | head -n 1)
    else
      LOCAL_FE_HOST=$(hostname -I | awk '{print $1}')
    fi
  fi
  LOCAL_FE_FLAGS="${LOCAL_FE_FLAGS} --set localFEAddress=${LOCAL_FE_HOST}:${LOCAL_FE_PORT}";
fi

CLOUD_VERSION_FLAGS=""
if [[ "${CLOUD_VERSION}" == "true" ]]; then
  CLOUD_VERSION_FLAGS="--set standalone=false"
fi


if $HELM_UPDATE; then
  helm repo add bitnami https://charts.bitnami.com/bitnami
  helm repo add clickhouse-operator https://docs.altinity.com/clickhouse-operator
  helm dependency build
fi
helm upgrade --install opik -n opik --create-namespace -f values.yaml \
    --set registry=${DOCKER_REGISTRY_LOCAL} \
    --set component.backend.image.tag=$VERSION --set component.frontend.image.tag=$VERSION \
    ${LOCAL_FE_FLAGS} ${CLOUD_VERSION_FLAGS} .

cd -
kubectl config set-context --current --namespace=opik
echo "Delete current pods"
kubectl delete po -l component=${OPIK_BACKEND}
kubectl delete po -l component=${OPIK_FRONTEND}

echo
echo "### Check if the pods are running"

TIMEOUT=180  # Timeout in seconds (e.g., 3 minutes)
INTERVAL=5   # Interval in seconds between checks
POD_NAME=${OPIK_BACKEND}

is_pod_running() {
  kubectl get pods| grep $POD_NAME | grep Running &> /dev/null
  return $?
}
START_TIME=$(date +%s)
while true; do
  if is_pod_running; then
    echo "Pod $POD_NAME is running "
    echo
    break
  else
    echo "Checking if pods are running..."
  fi
  # Check if the timeout has been reached
  CURRENT_TIME=$(date +%s)
  ELAPSED_TIME=$((CURRENT_TIME - START_TIME))
  if [ $ELAPSED_TIME -ge $TIMEOUT ]; then
    echo "Timeout reached: Pod $POD_NAME is not running"
    echo "To check run 'kubectl get pods' and continue investigating from there"
    exit 1
  fi
  sleep $INTERVAL
done

echo "### Waiting for pods"
kubectl wait --for=condition=ready pod --all

echo "### Port-forward Opik Frontend to local host"
# remove the previous port-forward
ps -ef | grep "svc/${OPIK_FRONTEND} ${OPIK_FRONTEND_PORT}" | grep -v grep | awk '{print $2}' | xargs kill 2>/dev/null|| true
kubectl port-forward svc/${OPIK_FRONTEND} ${OPIK_FRONTEND_PORT} > /dev/null 2>&1 &

echo "### Port-forward Opik Backend to local host"
# remove the previous port-forward
ps -ef | grep "svc/${OPIK_BACKEND} ${OPIK_BACKEND_PORT}" | grep -v grep | awk '{print $2}' | xargs kill 2>/dev/null|| true
kubectl port-forward svc/${OPIK_BACKEND} ${OPIK_BACKEND_PORT} > /dev/null 2>&1 &

echo "### Port-forward Open API to local host"
# remove the previous port-forward
ps -ef | grep "svc/${OPIK_BACKEND} ${OPIK_OPENAPI_PORT}" | grep -v grep | awk '{print $2}' | xargs kill 2>/dev/null|| true
kubectl port-forward svc/${OPIK_BACKEND} ${OPIK_OPENAPI_PORT} > /dev/null 2>&1 &

echo "### Port-forward Clickhouse to local host"
# remove the previous port-forward
ps -ef | grep "svc/${OPIK_CLICKHOUSE} ${OPIK_CLICKHOUSE_PORT}" | grep -v grep | awk '{print $2}' | xargs kill 2>/dev/null|| true
kubectl port-forward svc/${OPIK_CLICKHOUSE} ${OPIK_CLICKHOUSE_PORT} > /dev/null 2>&1 &

echo "### Port-forward MySQL to local host"
# remove the previous port-forward
ps -ef | grep "svc/${OPIK_MYSQL} ${OPIK_MYSQL_PORT}" | grep -v grep | awk '{print $2}' | xargs kill 2>/dev/null|| true
kubectl port-forward svc/${OPIK_MYSQL} ${OPIK_MYSQL_PORT} > /dev/null 2>&1 &

echo "### Port-forward REDIS to local host"
# remove the previous port-forward
ps -ef | grep "svc/${OPIK_REDIS} ${OPIK_REDIS_PORT}" | grep -v grep | awk '{print $2}' | xargs kill 2>/dev/null|| true
kubectl port-forward svc/${OPIK_REDIS} ${OPIK_REDIS_PORT} > /dev/null 2>&1 &

echo "Now you can open your browser and connect http://localhost:${OPIK_FRONTEND_PORT}"
