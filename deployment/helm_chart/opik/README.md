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
| https://charts.bitnami.com/bitnami | minio | 15.0.7 |
| https://charts.bitnami.com/bitnami | mysql | 11.1.9 |
| https://charts.bitnami.com/bitnami | redis | 18.19.2 |
| https://charts.bitnami.com/bitnami | zookeeper | 12.12.1 |
| https://docs.altinity.com/clickhouse-operator/ | altinity-clickhouse-operator | 0.23.7 |
| oci://registry-1.docker.io/bitnamicharts | common | 2.x.x |

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| altinity-clickhouse-operator.metrics.enabled | bool | `false` |  |
| altinity-clickhouse-operator.serviceMonitor.enabled | bool | `false` |  |
| basicAuth | bool | `false` |  |
| clickhouse.adminUser.password | string | `"opik"` |  |
| clickhouse.adminUser.useSecret.enabled | bool | `false` |  |
| clickhouse.adminUser.username | string | `"opik"` |  |
| clickhouse.backup.enabled | bool | `false` |  |
| clickhouse.backup.serviceAccount.annotations | object | `{}` |  |
| clickhouse.backup.serviceAccount.create | bool | `true` |  |
| clickhouse.backup.serviceAccount.enabled | bool | `false` |  |
| clickhouse.backup.successfulJobsHistoryLimit | int | `1` |  |
| clickhouse.enabled | bool | `true` |  |
| clickhouse.image | string | `"altinity/clickhouse-server:24.3.5.47.altinitystable"` |  |
| clickhouse.logsLevel | string | `"information"` |  |
| clickhouse.monitoring.enabled | bool | `false` |  |
| clickhouse.monitoring.password | string | `"opikmon"` |  |
| clickhouse.monitoring.username | string | `"opikmon"` |  |
| clickhouse.replicasCount | int | `1` |  |
| clickhouse.service.serviceTemplate | string | `"clickhouse-cluster-svc-template"` |  |
| clickhouse.shardsCount | int | `1` |  |
| clickhouse.storage | string | `"50Gi"` |  |
| clickhouse.zookeeper.host | string | `"opik-zookeeper"` |  |
| component.backend.autoscaling.enabled | bool | `false` |  |
| component.backend.backendConfigMap.enabled | bool | `true` |  |
| component.backend.enabled | bool | `true` |  |
| component.backend.env.ANALYTICS_DB_DATABASE_NAME | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_HOST | string | `"clickhouse-opik-clickhouse"` |  |
| component.backend.env.ANALYTICS_DB_MIGRATIONS_PASS | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_MIGRATIONS_URL | string | `"jdbc:clickhouse://clickhouse-opik-clickhouse:8123"` |  |
| component.backend.env.ANALYTICS_DB_MIGRATIONS_USER | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_PASS | string | `"opik"` |  |
| component.backend.env.ANALYTICS_DB_PORT | string | `"8123"` |  |
| component.backend.env.ANALYTICS_DB_PROTOCOL | string | `"HTTP"` |  |
| component.backend.env.ANALYTICS_DB_USERNAME | string | `"opik"` |  |
| component.backend.env.JAVA_OPTS | string | `"-Dliquibase.propertySubstitutionEnabled=true -XX:+UseG1GC -XX:MaxRAMPercentage=80.0 -XX:MinRAMPercentage=75"` |  |
| component.backend.env.OPIK_OTEL_SDK_ENABLED | bool | `false` |  |
| component.backend.env.OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED | bool | `true` |  |
| component.backend.env.OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS | string | `"process.command_args"` |  |
| component.backend.env.OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION | string | `"BASE2_EXPONENTIAL_BUCKET_HISTOGRAM"` |  |
| component.backend.env.OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE | string | `"delta"` |  |
| component.backend.env.OTEL_PROPAGATORS | string | `"tracecontext,baggage,b3"` |  |
| component.backend.env.OTEL_VERSION | string | `"2.12.0"` |  |
| component.backend.env.PYTHON_EVALUATOR_URL | string | `"http://opik-python-backend:8000"` |  |
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
| component.backend.livenessProbe.path | string | `"/health-check?name=all&type=alive"` |  |
| component.backend.livenessProbe.port | int | `8080` |  |
| component.backend.metrics.enabled | bool | `false` |  |
| component.backend.readinessProbe.initialDelaySeconds | int | `20` |  |
| component.backend.readinessProbe.path | string | `"/health-check?name=all&type=ready"` |  |
| component.backend.readinessProbe.port | int | `8080` |  |
| component.backend.replicaCount | int | `1` |  |
| component.backend.run_migration | bool | `true` |  |
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
| component.backend.serviceAccount.enabled | bool | `true` |  |
| component.backend.serviceAccount.name | string | `"opik-backend"` |  |
| component.backend.waitForClickhouse.clickhouse.host | string | `"clickhouse-opik-clickhouse"` |  |
| component.backend.waitForClickhouse.clickhouse.port | int | `8123` |  |
| component.backend.waitForClickhouse.clickhouse.protocol | string | `"http"` |  |
| component.backend.waitForClickhouse.image.registry | string | `"docker.io"` |  |
| component.backend.waitForClickhouse.image.repository | string | `"curlimages/curl"` |  |
| component.backend.waitForClickhouse.image.tag | string | `"8.12.1"` |  |
| component.frontend.autoscaling.enabled | bool | `false` |  |
| component.frontend.awsResolver | bool | `false` |  |
| component.frontend.backendConfigMap.enabled | bool | `false` |  |
| component.frontend.enabled | bool | `true` |  |
| component.frontend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.frontend.image.repository | string | `"opik-frontend"` |  |
| component.frontend.image.tag | string | `"latest"` |  |
| component.frontend.ingress.enabled | bool | `false` |  |
| component.frontend.logFormat | string | `"logger-json"` |  |
| component.frontend.logFormats.logger-json | string | `"escape=json '{ \"body_bytes_sent\": $body_bytes_sent, \"http_referer\": \"$http_referer\", \"http_user_agent\": \"$http_user_agent\", \"remote_addr\": \"$remote_addr\", \"remote_user\": \"$remote_user\", \"request\": \"$request\", \"status\": $status, \"time_local\": \"$time_local\", \"x_forwarded_for\": \"$http_x_forwarded_for\" }'"` |  |
| component.frontend.maps | list | `[]` |  |
| component.frontend.metrics.enabled | bool | `false` |  |
| component.frontend.replicaCount | int | `1` |  |
| component.frontend.service.ports[0].name | string | `"http"` |  |
| component.frontend.service.ports[0].port | int | `5173` |  |
| component.frontend.service.ports[0].protocol | string | `"TCP"` |  |
| component.frontend.service.ports[0].targetPort | int | `5173` |  |
| component.frontend.service.type | string | `"ClusterIP"` |  |
| component.frontend.serviceAccount.create | bool | `true` |  |
| component.frontend.serviceAccount.enabled | bool | `true` |  |
| component.frontend.serviceAccount.name | string | `"opik-frontend"` |  |
| component.frontend.throttling | object | `{}` |  |
| component.frontend.upstreamConfig | object | `{}` |  |
| component.frontend.volumeMounts[0].mountPath | string | `"/etc/nginx/conf.d/"` |  |
| component.frontend.volumeMounts[0].name | string | `"opik-frontend-nginx"` |  |
| component.frontend.volumes[0].configMap.items[0].key | string | `"default.conf"` |  |
| component.frontend.volumes[0].configMap.items[0].path | string | `"default.conf"` |  |
| component.frontend.volumes[0].configMap.name | string | `"opik-frontend-nginx"` |  |
| component.frontend.volumes[0].name | string | `"opik-frontend-nginx"` |  |
| component.python-backend.autoscaling.enabled | bool | `false` |  |
| component.python-backend.backendConfigMap.enabled | bool | `true` |  |
| component.python-backend.enabled | bool | `true` |  |
| component.python-backend.env.OPIK_REVERSE_PROXY_URL | string | `"http://opik-frontend:5173/api"` |  |
| component.python-backend.env.OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED | bool | `true` |  |
| component.python-backend.env.OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE | string | `"cumulative"` |  |
| component.python-backend.env.OTEL_METRIC_EXPORT_INTERVAL | string | `"60000"` |  |
| component.python-backend.env.OTEL_PROPAGATORS | string | `"tracecontext,baggage"` |  |
| component.python-backend.env.OTEL_SERVICE_NAME | string | `"opik-python-backend"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS | string | `"3"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_IMAGE_NAME | string | `"opik-sandbox-executor-python"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY | string | `"ghcr.io/comet-ml/opik"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_IMAGE_TAG | string | `"latest"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_PARALLEL_NUM | string | `"5"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_STRATEGY | string | `"process"` |  |
| component.python-backend.envFrom[0].configMapRef.name | string | `"opik-python-backend"` |  |
| component.python-backend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.python-backend.image.repository | string | `"opik-python-backend"` |  |
| component.python-backend.image.tag | string | `"latest"` |  |
| component.python-backend.ingress.enabled | bool | `false` |  |
| component.python-backend.metrics.enabled | bool | `false` |  |
| component.python-backend.networkPolicy.enabled | bool | `true` |  |
| component.python-backend.networkPolicy.engineEgress.except[0] | string | `"10.0.0.0/8"` |  |
| component.python-backend.networkPolicy.engineEgress.except[1] | string | `"100.64.0.0/10"` |  |
| component.python-backend.networkPolicy.engineEgress.except[2] | string | `"172.16.0.0/12"` |  |
| component.python-backend.networkPolicy.engineEgress.except[3] | string | `"192.0.0.0/24"` |  |
| component.python-backend.networkPolicy.engineEgress.except[4] | string | `"198.18.0.0/15"` |  |
| component.python-backend.networkPolicy.engineEgress.except[5] | string | `"192.168.0.0/16"` |  |
| component.python-backend.networkPolicy.engineEgress.ipBlock | string | `"0.0.0.0/0"` |  |
| component.python-backend.replicaCount | int | `1` |  |
| component.python-backend.securityContext.privileged | bool | `true` |  |
| component.python-backend.service.ports[0].name | string | `"http"` |  |
| component.python-backend.service.ports[0].port | int | `8000` |  |
| component.python-backend.service.ports[0].protocol | string | `"TCP"` |  |
| component.python-backend.service.ports[0].targetPort | int | `8000` |  |
| component.python-backend.service.type | string | `"ClusterIP"` |  |
| component.python-backend.serviceAccount.create | bool | `true` |  |
| component.python-backend.serviceAccount.enabled | bool | `true` |  |
| component.python-backend.serviceAccount.name | string | `"opik-python-backend"` |  |
| demoDataJob | bool | `true` |  |
| fullnameOverride | string | `""` |  |
| localFE | bool | `false` |  |
| localFEAddress | string | `"host.minikube.internal:5174"` |  |
| minio.auth.rootPassword | string | `"LESlrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"` |  |
| minio.auth.rootUser | string | `"THAAIOSFODNN7EXAMPLE"` |  |
| minio.disableWebUI | bool | `true` |  |
| minio.enabled | bool | `true` |  |
| minio.fullnameOverride | string | `"opik-minio"` |  |
| minio.mode | string | `"standalone"` |  |
| minio.persistence.enabled | bool | `true` |  |
| minio.persistence.size | string | `"50Gi"` |  |
| minio.provisioning.enabled | bool | `true` |  |
| minio.provisioning.extraCommands[0] | string | `"mc alias set s3 http://opik-minio:9000 THAAIOSFODNN7EXAMPLE LESlrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY --api S3v4"` |  |
| minio.provisioning.extraCommands[1] | string | `"mc mb --ignore-existing s3/public"` |  |
| minio.provisioning.extraCommands[2] | string | `"mc anonymous set download s3/public/"` |  |
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
| zookeeper.enabled | bool | `true` |  |
| zookeeper.env.ZK_HEAP_SIZE | string | `"512M"` |  |
| zookeeper.fullnameOverride | string | `"opik-zookeeper"` |  |
| zookeeper.headless.publishNotReadyAddresses | bool | `true` |  |
| zookeeper.persistence.enabled | bool | `true` |  |
| zookeeper.persistence.size | string | `"50Gi"` |  |
| zookeeper.podDisruptionBudget.enabled | bool | `true` |  |
| zookeeper.replicaCount | int | `1` |  |

----------------------------------------------
Autogenerated from chart metadata using [helm-docs v1.14.2](https://github.com/norwoodj/helm-docs/releases/v1.14.2)