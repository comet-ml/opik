# opik

A Helm chart for Comet Opik

# Run Comet Opik with Helm

## Installation Prerequisites for local installation

- Docker - https://docs.docker.com/engine/install/

- kubectl - https://kubernetes.io/docs/tasks/tools/#kubectl

- Helm - https://helm.sh/docs/intro/install/

- minikube - https://minikube.sigs.k8s.io/docs/start

- more tools:
    - **`bash`** completion / `zsh` completion
    - `kubectx` and `kubens` - easy switch context/namespaces for kubectl -  https://github.com/ahmetb/kubectx

## Run k8s cluster locally

Start your `minikube` cluster https://minikube.sigs.k8s.io/docs/start/

```bash
minikube start
```

## Installing the Chart

### Using helm chart from Helm repo

Add Opik Helm repo
```bash
helm repo add opik https://comet-ml.github.io/opik/
helm repo update
```

Set VERSION you want to install and run helm install

```bash
VERSION=0.1.0
helm upgrade --install opik -n opik --create-namespace opik/opik \
    --set component.backend.image.tag=$VERSION --set component.frontend.image.tag=$VERSION
```

### Using helm chart from git repository

```bash
git clone git@github.com:comet-ml/opik.git
```

Go to the chart folder, set VERSION you want to install and run helm install

```bash
cd deployment/helm_chart/opik
helm repo add bitnami https://charts.bitnami.com/bitnami
helm dependency build
VERSION=0.1.0
helm upgrade --install opik -n opik --create-namespace -f values.yaml \
    --set component.backend.image.tag=$VERSION --set component.frontend.image.tag=$VERSION
```

## Open application

You can port-forward any service you need to your local machine. For Opik Frontend and Backend api run
```console
$ kubectl port-forward -n opik svc/opik-frontend 5173
```
Open http://localhost:5173 in your browser

Call opik api on http://localhost:5173/api

# Helm Chart Details

## Requirements

| Repository | Name | Version |
|------------|------|---------|
| https://charts.bitnami.com/bitnami | mysql | 11.1.9 |
| https://charts.bitnami.com/bitnami | redis | 18.19.2 |
| https://charts.bitnami.com/bitnami | zookeeper | 12.12.1 |

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| basicAuth | bool | `false` |  |
| clickhouse.adminUser.password | string | `"opik"` |  |
| clickhouse.adminUser.useSecret.enabled | bool | `false` |  |
| clickhouse.adminUser.username | string | `"opik"` |  |
| clickhouse.backup.enabled | bool | `false` |  |
| clickhouse.image | string | `"altinity/clickhouse-server:24.3.5.47.altinitystable"` |  |
| clickhouse.logsLevel | string | `"information"` |  |
| clickhouse.namePrefix | string | `"opik"` |  |
| clickhouse.operator.enabled | bool | `true` |  |
| clickhouse.replicasCount | int | `1` |  |
| clickhouse.service.serviceTemplate | string | `"clickhouse-cluster-svc-template"` |  |
| clickhouse.shardsCount | int | `1` |  |
| clickhouse.storage | string | `"50Gi"` |  |
| component.backend.autoscaling.enabled | bool | `false` |  |
| component.backend.env.ANALYTICS_DB_DATABASE_NAME | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_HOST | string | `"clickhouse-opik-clickhouse"` |  |
| component.backend.env.ANALYTICS_DB_MIGRATIONS_PASS | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_MIGRATIONS_URL | string | `"jdbc:clickhouse://clickhouse-opik-clickhouse:8123"` |  |
| component.backend.env.ANALYTICS_DB_MIGRATIONS_USER | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_PASS | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_PORT | string | `"8123"` |  |
| component.backend.env.ANALYTICS_DB_PROTOCOL | string | `"HTTP"` |  |
| component.backend.env.ANALYTICS_DB_USERNAME | string | `"opik"` |  |
| component.backend.env.JAVA_OPTS | string | `"-Dliquibase.propertySubstitutionEnabled=true"` |  |
| component.backend.env.OPIK_OTEL_SDK_ENABLED | bool | `false` |  |
| component.backend.env.OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED | bool | `true` |  |
| component.backend.env.OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS | string | `"process.command_args"` |  |
| component.backend.env.OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION | string | `"BASE2_EXPONENTIAL_BUCKET_HISTOGRAM"` |  |
| component.backend.env.OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE | string | `"delta"` |  |
| component.backend.env.OTEL_PROPAGATORS | string | `"tracecontext,baggage,b3"` |  |
| component.backend.env.OTEL_VERSION | string | `"2.8.0"` |  |
| component.backend.env.REDIS_URL | string | `"redis://:wFSuJX9nDBdCa25sKZG7bh@opik-redis-master:6379/"` |  |
| component.backend.env.STATE_DB_DATABASE_NAME | string | `"opik"` |  |
| component.backend.env.STATE_DB_PASS | string | `"opik"` |  |
| component.backend.env.STATE_DB_PROTOCOL | string | `"jdbc:mysql://"` |  |
| component.backend.env.STATE_DB_URL | string | `"opik-mysql:3306/opik?rewriteBatchedStatements=true"` |  |
| component.backend.env.STATE_DB_USER | string | `"opik"` |  |
| component.backend.envFrom[0].configMapRef.name | string | `"opik-backend"` |  |
| component.backend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.backend.image.repository | string | `"opik-backend"` |  |
| component.backend.image.tag | string | `"latest"` |  |
| component.backend.ingress.enabled | bool | `false` |  |
| component.backend.initContainers[0].env[0].name | string | `"URL"` |  |
| component.backend.initContainers[0].env[0].value | string | `"http://clickhouse-opik-clickhouse:8123"` |  |
| component.backend.initContainers[0].image | string | `"stefanevinance/wait-for-200"` |  |
| component.backend.initContainers[0].name | string | `"wait-for-clickhouse-service"` |  |
| component.backend.replicaCount | int | `1` |  |
| component.backend.service.ports[0].name | string | `"http"` |  |
| component.backend.service.ports[0].port | int | `8080` |  |
| component.backend.service.ports[0].protocol | string | `"TCP"` |  |
| component.backend.service.ports[0].targetPort | int | `8080` |  |
| component.backend.service.ports[1].name | string | `"backend"` |  |
| component.backend.service.ports[1].port | int | `3003` |  |
| component.backend.service.ports[1].protocol | string | `"TCP"` |  |
| component.backend.service.ports[1].targetPort | int | `3003` |  |
| component.backend.service.type | string | `"ClusterIP"` |  |
| component.backend.serviceAccount.create | bool | `true` |  |
| component.frontend.autoscaling.enabled | bool | `false` |  |
| component.frontend.awsResolver | bool | `false` |  |
| component.frontend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.frontend.image.repository | string | `"opik-frontend"` |  |
| component.frontend.image.tag | string | `"latest"` |  |
| component.frontend.ingress.enabled | bool | `false` |  |
| component.frontend.logFormat | string | `"logger-json"` |  |
| component.frontend.logFormats.logger-json | string | `"escape=json '{ \"body_bytes_sent\": $body_bytes_sent, \"http_referer\": \"$http_referer\", \"http_user_agent\": \"$http_user_agent\", \"remote_addr\": \"$remote_addr\", \"remote_user\": \"$remote_user\", \"request\": \"$request\", \"status\": $status, \"time_local\": \"$time_local\", \"x_forwarded_for\": \"$http_x_forwarded_for\" }'"` |  |
| component.frontend.maps | list | `[]` |  |
| component.frontend.replicaCount | int | `1` |  |
| component.frontend.service.ports[0].name | string | `"http"` |  |
| component.frontend.service.ports[0].port | int | `5173` |  |
| component.frontend.service.ports[0].protocol | string | `"TCP"` |  |
| component.frontend.service.ports[0].targetPort | int | `5173` |  |
| component.frontend.service.type | string | `"ClusterIP"` |  |
| component.frontend.serviceAccount.create | bool | `true` |  |
| component.frontend.throttling | object | `{}` |  |
| component.frontend.volumeMounts[0].mountPath | string | `"/etc/nginx/conf.d/"` |  |
| component.frontend.volumeMounts[0].name | string | `"opik-frontend-nginx"` |  |
| component.frontend.volumes[0].configMap.items[0].key | string | `"default.conf"` |  |
| component.frontend.volumes[0].configMap.items[0].path | string | `"default.conf"` |  |
| component.frontend.volumes[0].configMap.name | string | `"opik-frontend-nginx"` |  |
| component.frontend.volumes[0].name | string | `"opik-frontend-nginx"` |  |
| fullnameOverride | string | `""` |  |
| localFE | bool | `false` |  |
| localFEAddress | string | `"host.minikube.internal:5174"` |  |
| mysql.auth.rootPassword | string | `"opik"` |  |
| mysql.enabled | bool | `true` |  |
| mysql.fullnameOverride | string | `"opik-mysql"` |  |
| mysql.initdbScripts."createdb.sql" | string | `"CREATE DATABASE IF NOT EXISTS opik DEFAULT CHARACTER SET utf8 DEFAULT COLLATE utf8_general_ci;\nCREATE USER IF NOT EXISTS 'opik'@'%' IDENTIFIED BY 'opik';\nGRANT ALL ON `opik`.* TO 'opik'@'%';\nFLUSH PRIVILEGES;"` |  |
| nameOverride | string | `"opik"` |  |
| redis.architecture | string | `"standalone"` |  |
| redis.auth.password | string | `"wFSuJX9nDBdCa25sKZG7bh"` |  |
| redis.enabled | bool | `true` |  |
| redis.extraDeploy[0].apiVersion | string | `"v1"` |  |
| redis.extraDeploy[0].data."start-master.sh" | string | `"#!/usr/bin/dumb-init /bin/bash\n### docker entrypoint script, for starting redis stack\nBASEDIR=/opt/redis-stack\ncd ${BASEDIR}\nCMD=${BASEDIR}/bin/redis-server\nif [ -z \"${REDISEARCH_ARGS}\" ]; then\nREDISEARCH_ARGS=\"MAXSEARCHRESULTS 10000 MAXAGGREGATERESULTS 10000\"\nfi\n[[ -f $REDIS_PASSWORD_FILE ]] && export REDIS_PASSWORD=\"$(< \"${REDIS_PASSWORD_FILE}\")\"\nif [[ -f /opt/bitnami/redis/mounted-etc/master.conf ]];then\n    cp /opt/bitnami/redis/mounted-etc/master.conf /opt/bitnami/redis/etc/master.conf\nfi\nif [[ -f /opt/bitnami/redis/mounted-etc/redis.conf ]];then\n    cp /opt/bitnami/redis/mounted-etc/redis.conf /opt/bitnami/redis/etc/redis.conf\nfi\n${CMD} \\\n--port \"${REDIS_PORT}\" \\\n--requirepass \"${REDIS_PASSWORD}\" \\\n--masterauth \"${REDIS_PASSWORD}\" \\\n--include \"/opt/bitnami/redis/etc/redis.conf\" \\\n--include \"/opt/bitnami/redis/etc/master.conf\" \\\n--loadmodule ${BASEDIR}/lib/redisearch.so ${REDISEARCH_ARGS} \\\n--loadmodule ${BASEDIR}/lib/redistimeseries.so ${REDISTIMESERIES_ARGS} \\\n--loadmodule ${BASEDIR}/lib/rejson.so ${REDISJSON_ARGS} \\\n--loadmodule ${BASEDIR}/lib/redisbloom.so ${REDISBLOOM_ARGS}\n"` |  |
| redis.extraDeploy[0].kind | string | `"ConfigMap"` |  |
| redis.extraDeploy[0].metadata.name | string | `"bitnami-redis-stack-server-merged"` |  |
| redis.fullnameOverride | string | `"opik-redis"` |  |
| redis.image.repository | string | `"redis/redis-stack-server"` |  |
| redis.image.tag | string | `"7.2.0-v10"` |  |
| redis.master.args[0] | string | `"-c"` |  |
| redis.master.args[1] | string | `"/opt/bitnami/scripts/merged-start-scripts/start-master.sh"` |  |
| redis.master.configuration | string | `"maxmemory 105M"` |  |
| redis.master.extraVolumeMounts[0].mountPath | string | `"/opt/bitnami/scripts/merged-start-scripts"` |  |
| redis.master.extraVolumeMounts[0].name | string | `"merged-start-scripts"` |  |
| redis.master.extraVolumes[0].configMap.defaultMode | int | `493` |  |
| redis.master.extraVolumes[0].configMap.name | string | `"bitnami-redis-stack-server-merged"` |  |
| redis.master.extraVolumes[0].name | string | `"merged-start-scripts"` |  |
| redis.master.resources.limits.memory | string | `"1Gi"` |  |
| redis.master.resources.requests.cpu | string | `"15m"` |  |
| redis.master.resources.requests.memory | string | `"105M"` |  |
| redis.metrics.enabled | bool | `false` |  |
| redis.ssl | bool | `false` |  |
| registry | string | `"ghcr.io/comet-ml/opik"` |  |
| standalone | bool | `true` |  |
| zookeeper.enabled | bool | `false` |  |
| zookeeper.env.ZK_HEAP_SIZE | string | `"512M"` |  |
| zookeeper.fullnameOverride | string | `"opik-zookeeper"` |  |
| zookeeper.headless.publishNotReadyAddresses | bool | `true` |  |
| zookeeper.persistence.enabled | bool | `true` |  |
| zookeeper.persistence.size | string | `"50Gi"` |  |
| zookeeper.podDisruptionBudget.enabled | bool | `true` |  |
| zookeeper.replicaCount | int | `1` |  |

----------------------------------------------
Autogenerated from chart metadata using [helm-docs v1.14.2](https://github.com/norwoodj/helm-docs/releases/v1.14.2)