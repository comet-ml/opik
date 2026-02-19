# opik

A Helm chart for Comet Opik

![Version: 1.10.15](https://img.shields.io/badge/Version-1.10.15-informational?style=flat-square) ![Type: application](https://img.shields.io/badge/Type-application-informational?style=flat-square) ![AppVersion: 1.10.15](https://img.shields.io/badge/AppVersion-1.10.15-informational?style=flat-square)
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/opik)](https://artifacthub.io/packages/search?repo=opik)

# Run Comet Opik with Helm

## Installation Prerequisites for local installation

- Docker - https://docs.docker.com/engine/install/

- kubectl - https://kubernetes.io/docs/tasks/tools/#kubectl

- Helm - https://helm.sh/docs/intro/install/
requires Helm 3.10+

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
| https://comet-ml.github.io/comet-mysql-helm/ | mysql | 1.0.7 |
| https://docs.altinity.com/clickhouse-operator/ | altinity-clickhouse-operator | 0.25.6 |
| oci://registry-1.docker.io/cloudpirates | minio | 0.10.0 |
| oci://registry-1.docker.io/cloudpirates | redis | 0.23.0 |
| oci://registry-1.docker.io/cloudpirates | zookeeper | 0.6.0 |

## Values

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| affinity | object | `{}` |  |
| altinity-clickhouse-operator.enabled | bool | `true` |  |
| altinity-clickhouse-operator.metrics.enabled | bool | `false` |  |
| altinity-clickhouse-operator.serviceMonitor.enabled | bool | `false` |  |
| altinity-clickhouse-operator.serviceMonitor.interval | string | `""` |  |
| basicAuth | bool | `false` |  |
| caCerts.additionalCACerts | list | `nil` | Additional Certificate Authority Certificates to trust. Each list entry has two keys: `name` and `content`. `name` should be a unique identifier. content Should be PEM formated Public Certificate contents. |
| caCerts.additionalCACertsInSecret | bool | `false` | Certificates are Public Keys + Metadata, but some organizations may still prefer storing these in Secrets. |
| caCerts.existingAdditionalCACertsRef | string | `nil` | If there is an existing ConfigMap containing the additional CA Certificates you can provide its name here instead of creatinga a new one. If `additionalCACertsInSecret` is `true` we will look for this name in Secrets. |
| caCerts.overwriteJavaCATrustStore.enabled | bool | `false` | If enabled we will not inject additional CA Certificates to the Java TrustStore, but instead will mount a given volume containing a Java Trust Store to replace the default one in the containers. |
| caCerts.overwriteJavaCATrustStore.subPath | string | `"cacerts"` | The sub-path of the valid Java Trust Store to mount from the volume. |
| caCerts.overwriteJavaCATrustStore.volume | object | `{}` | A Kubernetes Volume definition which will contain a valid Java Trust Store. See https://kubernetes.io/docs/reference/kubernetes-api/config-and-storage-resources/volume/ for valid parameters. |
| chartMigration.enabled | bool | `true` |  |
| chartMigration.image | string | `"alpine/kubectl:1.35.0"` |  |
| chartMigration.nodeSelector | object | `{}` |  |
| chartMigration.serviceAccountName | string | `""` |  |
| chartMigration.tolerations | list | `[]` |  |
| clickhouse.adminUser.password | string | `"opik"` |  |
| clickhouse.adminUser.useSecret.enabled | bool | `false` |  |
| clickhouse.adminUser.username | string | `"opik"` |  |
| clickhouse.backup.command[0] | string | `"/bin/bash"` |  |
| clickhouse.backup.command[1] | string | `"-cx"` |  |
| clickhouse.backup.command[2] | string | `"export backupname=backup$(date +'%Y%m%d%H%M')\necho \"BACKUP ALL EXCEPT DATABASE system TO S3('${CLICKHOUSE_BACKUP_BUCKET}/${backupname}/', '$ACCESS_KEY', '$SECRET_KEY');\" > /tmp/backQuery.sql\nclickhouse-client -h clickhouse-opik-clickhouse --send_timeout 600000 --receive_timeout 600000 --port 9000 --queries-file=/tmp/backQuery.sql"` |  |
| clickhouse.backup.enabled | bool | `false` |  |
| clickhouse.backup.extraEnv | object | `{}` |  |
| clickhouse.backup.schedule | string | `"0 0 * * *"` |  |
| clickhouse.backup.serviceAccount.annotations | object | `{}` |  |
| clickhouse.backup.serviceAccount.create | bool | `false` |  |
| clickhouse.backup.serviceAccount.name | string | `""` |  |
| clickhouse.backup.successfulJobsHistoryLimit | int | `1` |  |
| clickhouse.backupServer.enabled | bool | `false` |  |
| clickhouse.backupServer.env.ALLOW_EMPTY_BACKUPS | bool | `true` |  |
| clickhouse.backupServer.env.API_CREATE_INTEGRATION_TABLES | bool | `true` |  |
| clickhouse.backupServer.env.API_LISTEN | string | `"0.0.0.0:7171"` |  |
| clickhouse.backupServer.env.LOG_LEVEL | string | `"info"` |  |
| clickhouse.backupServer.image | string | `"altinity/clickhouse-backup:2.6.23"` |  |
| clickhouse.backupServer.monitoring.additionalLabels | object | `{}` |  |
| clickhouse.backupServer.monitoring.annotations | object | `{}` |  |
| clickhouse.backupServer.monitoring.enabled | bool | `false` |  |
| clickhouse.backupServer.monitoring.service.ports[0].name | string | `"ch-backup-rest"` |  |
| clickhouse.backupServer.monitoring.service.ports[0].port | int | `80` |  |
| clickhouse.backupServer.monitoring.service.ports[0].targetPort | int | `7171` |  |
| clickhouse.backupServer.monitoring.service.type | string | `"ClusterIP"` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.additionalLabels | object | `{}` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.annotations | object | `{}` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.enabled | bool | `false` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.honorLabels | bool | `false` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.interval | string | `"60s"` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.metricRelabelings | list | `[]` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.namespace | string | `""` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.podTargetLabels | list | `[]` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.portName | string | `""` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.relabelings | list | `[]` |  |
| clickhouse.backupServer.monitoring.serviceMonitor.scrapeTimeout | string | `"30s"` |  |
| clickhouse.backupServer.port | int | `7171` |  |
| clickhouse.configuration.files."conf.d/memory.xml" | string | `"<yandex>\n  <max_server_memory_usage_to_ram_ratio>0.85</max_server_memory_usage_to_ram_ratio>\n</yandex>\n"` |  |
| clickhouse.configuration.files."conf.d/profiles.xml" | string | `"<clickhouse>\n  <profiles>\n    <default>\n        <max_bytes_ratio_before_external_sort>0.2</max_bytes_ratio_before_external_sort>\n        <max_bytes_ratio_before_external_group_by>0.2</max_bytes_ratio_before_external_group_by>\n    </default>\n  </profiles>\n</clickhouse>\n"` |  |
| clickhouse.configuration.files."conf.d/system_tables.xml" | string | `"<clickhouse>\n  <opentelemetry_span_log remove=\"1\"/>\n  <asynchronous_metric_log remove=\"1\"/>\n  <processors_profile_log remove=\"1\"/>\n  <text_log remove=\"1\"/>\n  <trace_log remove=\"1\"/>\n  <blob_storage_log remove=\"1\"/>\n  <error_log>\n      <engine>\n          ENGINE MergeTree\n          PARTITION BY toYYYYMM(event_date)\n          ORDER BY (event_date, event_time)\n          TTL event_date + toIntervalDay(30)\n          SETTINGS index_granularity = 8192\n      </engine>\n      <database>system</database>\n      <table>error_log</table>\n  </error_log>\n  <latency_log>\n      <engine>\n          ENGINE = MergeTree\n          PARTITION BY toYYYYMM(event_date)\n          ORDER BY (event_date, event_time)\n          TTL event_date + toIntervalDay(30)\n          SETTINGS index_granularity = 8192\n      </engine>\n      <database>system</database>\n      <table>latency_log</table>\n  </latency_log>\n  <metric_log>\n      <engine>\n          ENGINE = MergeTree\n          PARTITION BY toYYYYMM(event_date)\n          ORDER BY (event_date, event_time)\n          TTL event_date + toIntervalDay(30)\n          SETTINGS index_granularity = 8192\n      </engine>\n      <database>system</database>\n      <table>metric_log</table>\n  </metric_log>\n  <query_metric_log>\n      <engine>\n          ENGINE = MergeTree\n          PARTITION BY toYYYYMM(event_date)\n          ORDER BY (event_date, event_time)\n          TTL event_date + toIntervalDay(30)\n          SETTINGS index_granularity = 8192\n      </engine>\n      <database>system</database>\n      <table>query_metric_log</table>\n  </query_metric_log>\n</clickhouse>\n"` |  |
| clickhouse.enabled | bool | `true` |  |
| clickhouse.extraPodTemplates | list | `[]` |  |
| clickhouse.extraServiceTemplates | list | `[]` |  |
| clickhouse.extraVolumeClaimTemplates | list | `[]` |  |
| clickhouse.image | string | `"altinity/clickhouse-server:25.3.8.10041.altinitystable"` |  |
| clickhouse.livenessProbe.failureThreshold | int | `10` |  |
| clickhouse.livenessProbe.httpGet.path | string | `"/ping"` |  |
| clickhouse.livenessProbe.httpGet.port | int | `8123` |  |
| clickhouse.livenessProbe.initialDelaySeconds | int | `60` |  |
| clickhouse.livenessProbe.periodSeconds | int | `30` |  |
| clickhouse.livenessProbe.timeoutSeconds | int | `5` |  |
| clickhouse.logsLevel | string | `"information"` |  |
| clickhouse.monitoring.additionalLabels | object | `{}` |  |
| clickhouse.monitoring.annotations | object | `{}` |  |
| clickhouse.monitoring.enabled | bool | `false` |  |
| clickhouse.monitoring.password | string | `"opikmon"` |  |
| clickhouse.monitoring.port | int | `9363` |  |
| clickhouse.monitoring.service.ports[0].name | string | `"prometheus-metrics"` |  |
| clickhouse.monitoring.service.ports[0].port | int | `80` |  |
| clickhouse.monitoring.service.ports[0].targetPort | int | `9363` |  |
| clickhouse.monitoring.service.type | string | `"ClusterIP"` |  |
| clickhouse.monitoring.serviceMonitor.additionalLabels | object | `{}` |  |
| clickhouse.monitoring.serviceMonitor.annotations | object | `{}` |  |
| clickhouse.monitoring.serviceMonitor.enabled | bool | `false` |  |
| clickhouse.monitoring.serviceMonitor.honorLabels | bool | `false` |  |
| clickhouse.monitoring.serviceMonitor.interval | string | `"60s"` |  |
| clickhouse.monitoring.serviceMonitor.metricRelabelings | list | `[]` |  |
| clickhouse.monitoring.serviceMonitor.namespace | string | `""` |  |
| clickhouse.monitoring.serviceMonitor.podTargetLabels | list | `[]` |  |
| clickhouse.monitoring.serviceMonitor.portName | string | `""` |  |
| clickhouse.monitoring.serviceMonitor.relabelings | list | `[]` |  |
| clickhouse.monitoring.serviceMonitor.scrapeTimeout | string | `"30s"` |  |
| clickhouse.monitoring.useSecret.enabled | bool | `false` |  |
| clickhouse.monitoring.username | string | `"opikmon"` |  |
| clickhouse.namespaceDomainPattern | string | `""` |  |
| clickhouse.readinessProbe.failureThreshold | int | `30` |  |
| clickhouse.readinessProbe.httpGet.path | string | `"/ping"` |  |
| clickhouse.readinessProbe.httpGet.port | int | `8123` |  |
| clickhouse.readinessProbe.initialDelaySeconds | int | `30` |  |
| clickhouse.readinessProbe.periodSeconds | int | `10` |  |
| clickhouse.readinessProbe.timeoutSeconds | int | `5` |  |
| clickhouse.replicasCount | int | `1` |  |
| clickhouse.service.serviceTemplate | string | `"clickhouse-cluster-svc-template"` |  |
| clickhouse.serviceAccount.annotations | object | `{}` |  |
| clickhouse.serviceAccount.create | bool | `false` |  |
| clickhouse.serviceAccount.name | string | `""` |  |
| clickhouse.shardsCount | int | `1` |  |
| clickhouse.storage | string | `"50Gi"` |  |
| clickhouse.templates.podTemplate | string | `"clickhouse-cluster-pod-template"` |  |
| clickhouse.templates.replicaServiceTemplate | string | `"clickhouse-replica-svc-template"` |  |
| clickhouse.templates.serviceTemplate | string | `"clickhouse-cluster-svc-template"` |  |
| clickhouse.templates.volumeClaimTemplate | string | `"storage-vc-template"` |  |
| clickhouse.zookeeper.host | string | `"opik-zookeeper"` |  |
| component.ai-backend.autoscaling.enabled | bool | `false` |  |
| component.ai-backend.backendConfigMap.enabled | bool | `true` |  |
| component.ai-backend.enabled | bool | `false` |  |
| component.ai-backend.env.AGENT_OPIK_URL | string | `"http://opik-backend:8080"` |  |
| component.ai-backend.env.OPIK_URL_OVERRIDE | string | `"http://opik-backend:8080"` |  |
| component.ai-backend.env.PORT | string | `"8081"` |  |
| component.ai-backend.env.SESSION_SERVICE_URI | string | `"mysql://opik:opik@opik-mysql:3306/opik"` |  |
| component.ai-backend.env.URL_PREFIX | string | `"/opik-ai"` |  |
| component.ai-backend.envFrom[0].configMapRef.name | string | `"opik-ai-backend"` |  |
| component.ai-backend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.ai-backend.image.repository | string | `"opik-ai-backend"` |  |
| component.ai-backend.ingress.annotations | object | `{}` |  |
| component.ai-backend.ingress.enabled | bool | `false` |  |
| component.ai-backend.ingress.hosts | list | `[]` |  |
| component.ai-backend.ingress.ingressClassName | string | `""` |  |
| component.ai-backend.ingress.tls.enabled | bool | `false` |  |
| component.ai-backend.ingress.tls.hosts | list | `[]` |  |
| component.ai-backend.ingress.tls.secretName | string | `""` |  |
| component.ai-backend.livenessProbe.httpGet.path | string | `"/opik-ai/healthz"` |  |
| component.ai-backend.livenessProbe.httpGet.port | int | `8081` |  |
| component.ai-backend.metrics.enabled | bool | `false` |  |
| component.ai-backend.podDisruptionBudget.enabled | bool | `false` |  |
| component.ai-backend.ports[0].containerPort | int | `8081` |  |
| component.ai-backend.ports[0].name | string | `"http"` |  |
| component.ai-backend.ports[0].protocol | string | `"TCP"` |  |
| component.ai-backend.readinessProbe.httpGet.path | string | `"/opik-ai/healthz"` |  |
| component.ai-backend.readinessProbe.httpGet.port | int | `8081` |  |
| component.ai-backend.readinessProbe.initialDelaySeconds | int | `10` |  |
| component.ai-backend.replicaCount | int | `1` |  |
| component.ai-backend.secretRefs | list | `[]` |  |
| component.ai-backend.securityContext | object | `{}` |  |
| component.ai-backend.service.ports[0].name | string | `"http"` |  |
| component.ai-backend.service.ports[0].port | int | `8081` |  |
| component.ai-backend.service.ports[0].protocol | string | `"TCP"` |  |
| component.ai-backend.service.ports[0].targetPort | int | `8081` |  |
| component.ai-backend.service.type | string | `"ClusterIP"` |  |
| component.ai-backend.serviceAccount.create | bool | `true` |  |
| component.ai-backend.serviceAccount.name | string | `"opik-ai-backend"` |  |
| component.ai-backend.startupProbe.failureThreshold | int | `30` |  |
| component.ai-backend.startupProbe.httpGet.path | string | `"/opik-ai/healthz"` |  |
| component.ai-backend.startupProbe.httpGet.port | int | `8081` |  |
| component.ai-backend.startupProbe.periodSeconds | int | `10` |  |
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
| component.backend.ingress.annotations | object | `{}` |  |
| component.backend.ingress.enabled | bool | `false` |  |
| component.backend.ingress.hosts | list | `[]` |  |
| component.backend.ingress.ingressClassName | string | `""` |  |
| component.backend.ingress.tls.enabled | bool | `false` |  |
| component.backend.ingress.tls.hosts | list | `[]` |  |
| component.backend.ingress.tls.secretName | string | `""` |  |
| component.backend.livenessProbe.httpGet.httpHeaders[0].name | string | `"Accept"` |  |
| component.backend.livenessProbe.httpGet.httpHeaders[0].value | string | `"application/json"` |  |
| component.backend.livenessProbe.httpGet.path | string | `"/health-check?name=all&type=alive"` |  |
| component.backend.livenessProbe.httpGet.port | int | `8080` |  |
| component.backend.metrics.enabled | bool | `false` |  |
| component.backend.podDisruptionBudget.enabled | bool | `false` |  |
| component.backend.readinessProbe.httpGet.httpHeaders[0].name | string | `"Accept"` |  |
| component.backend.readinessProbe.httpGet.httpHeaders[0].value | string | `"application/json"` |  |
| component.backend.readinessProbe.httpGet.path | string | `"/health-check?name=all&type=ready"` |  |
| component.backend.readinessProbe.httpGet.port | int | `8080` |  |
| component.backend.readinessProbe.initialDelaySeconds | int | `20` |  |
| component.backend.replicaCount | int | `1` |  |
| component.backend.resources.requests.ephemeral-storage | string | `"10Gi"` |  |
| component.backend.run_migration | bool | `true` |  |
| component.backend.service.ports[0].name | string | `"http"` |  |
| component.backend.service.ports[0].port | int | `8080` |  |
| component.backend.service.ports[0].protocol | string | `"TCP"` |  |
| component.backend.service.ports[0].targetPort | int | `8080` |  |
| component.backend.service.ports[1].name | string | `"swagger"` |  |
| component.backend.service.ports[1].port | int | `3003` |  |
| component.backend.service.ports[1].protocol | string | `"TCP"` |  |
| component.backend.service.ports[1].targetPort | int | `3003` |  |
| component.backend.service.type | string | `"ClusterIP"` |  |
| component.backend.serviceAccount.create | bool | `true` |  |
| component.backend.serviceAccount.name | string | `"opik-backend"` |  |
| component.backend.waitForClickhouse.clickhouse.host | string | `"clickhouse-opik-clickhouse"` |  |
| component.backend.waitForClickhouse.clickhouse.port | int | `8123` |  |
| component.backend.waitForClickhouse.clickhouse.protocol | string | `"http"` |  |
| component.backend.waitForClickhouse.image.registry | string | `"docker.io"` |  |
| component.backend.waitForClickhouse.image.repository | string | `"curlimages/curl"` |  |
| component.backend.waitForClickhouse.image.tag | string | `"8.12.1"` |  |
| component.backend.waitForMysql.enabled | bool | `false` |  |
| component.backend.waitForMysql.image.registry | string | `"docker.io"` |  |
| component.backend.waitForMysql.image.repository | string | `"busybox"` |  |
| component.backend.waitForMysql.image.tag | float | `1.36` |  |
| component.backend.waitForMysql.mysql.host | string | `"opik-mysql"` |  |
| component.backend.waitForMysql.mysql.port | int | `3306` |  |
| component.frontend.aiBackendUpstreamConfig.gzip | string | `"off"` |  |
| component.frontend.aiBackendUpstreamConfig.proxy_buffering | string | `"off"` |  |
| component.frontend.aiBackendUpstreamConfig.proxy_connect_timeout | int | `90` |  |
| component.frontend.aiBackendUpstreamConfig.proxy_read_timeout | int | `300` |  |
| component.frontend.aiBackendUpstreamConfig.proxy_send_timeout | int | `300` |  |
| component.frontend.autoscaling.enabled | bool | `false` |  |
| component.frontend.awsResolver | bool | `false` |  |
| component.frontend.backendConfigMap.enabled | bool | `false` |  |
| component.frontend.cacheControl[0].pattern | string | `"~assets/.*\\.(js|css)$"` |  |
| component.frontend.cacheControl[0].value | string | `"public, max-age=604800, immutable"` |  |
| component.frontend.cacheControl[1].pattern | string | `"~(images/.*|assets/.*)\\.(jpg|jpeg|png|gif|svg|webp|ico)$"` |  |
| component.frontend.cacheControl[1].value | string | `"public, max-age=2592000"` |  |
| component.frontend.cacheControl[2].pattern | string | `"~assets/.*\\.(woff|woff2|ttf|eot)$"` |  |
| component.frontend.cacheControl[2].value | string | `"public, max-age=2592000"` |  |
| component.frontend.cacheControl[3].pattern | string | `"~assets/.*\\.json$"` |  |
| component.frontend.cacheControl[3].value | string | `"public, max-age=86400"` |  |
| component.frontend.cacheControl[4].pattern | string | `"default"` |  |
| component.frontend.cacheControl[4].value | string | `"no-cache, must-revalidate"` |  |
| component.frontend.contentSecurityPolicy.base-uri[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.child-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.connect-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.connect-src[1] | string | `"ws:"` |  |
| component.frontend.contentSecurityPolicy.connect-src[2] | string | `"wss:"` |  |
| component.frontend.contentSecurityPolicy.connect-src[3] | string | `"https:"` |  |
| component.frontend.contentSecurityPolicy.default-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.font-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.font-src[1] | string | `"data:"` |  |
| component.frontend.contentSecurityPolicy.font-src[2] | string | `"https://fonts.gstatic.com"` |  |
| component.frontend.contentSecurityPolicy.form-action[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.frame-ancestors[0] | string | `"'none'"` |  |
| component.frontend.contentSecurityPolicy.img-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.img-src[1] | string | `"data:"` |  |
| component.frontend.contentSecurityPolicy.img-src[2] | string | `"blob:"` |  |
| component.frontend.contentSecurityPolicy.img-src[3] | string | `"https:"` |  |
| component.frontend.contentSecurityPolicy.img-src[4] | string | `"http:"` |  |
| component.frontend.contentSecurityPolicy.manifest-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.media-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.media-src[1] | string | `"https:"` |  |
| component.frontend.contentSecurityPolicy.media-src[2] | string | `"http:"` |  |
| component.frontend.contentSecurityPolicy.object-src[0] | string | `"'none'"` |  |
| component.frontend.contentSecurityPolicy.script-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.script-src[1] | string | `"'unsafe-inline'"` |  |
| component.frontend.contentSecurityPolicy.script-src[2] | string | `"'unsafe-eval'"` |  |
| component.frontend.contentSecurityPolicy.style-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.style-src[1] | string | `"'unsafe-inline'"` |  |
| component.frontend.contentSecurityPolicy.style-src[2] | string | `"https://fonts.googleapis.com"` |  |
| component.frontend.contentSecurityPolicy.worker-src[0] | string | `"'self'"` |  |
| component.frontend.contentSecurityPolicy.worker-src[1] | string | `"blob:"` |  |
| component.frontend.enabled | bool | `true` |  |
| component.frontend.extraServerHeaders.X-Content-Type-Options | string | `"nosniff"` |  |
| component.frontend.extraServerHeaders.X-Frame-Options | string | `"DENY"` |  |
| component.frontend.extraServerHeaders.X-XSS-Protection | string | `"0"` |  |
| component.frontend.hstsEnabled | bool | `false` |  |
| component.frontend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.frontend.image.repository | string | `"opik-frontend"` |  |
| component.frontend.ingress.annotations | object | `{}` |  |
| component.frontend.ingress.enabled | bool | `false` |  |
| component.frontend.ingress.hosts | list | `[]` |  |
| component.frontend.ingress.ingressClassName | string | `""` |  |
| component.frontend.ingress.tls.enabled | bool | `false` |  |
| component.frontend.ingress.tls.hosts | list | `[]` |  |
| component.frontend.ingress.tls.secretName | string | `""` |  |
| component.frontend.maps | list | `[]` |  |
| component.frontend.metrics.enabled | bool | `false` |  |
| component.frontend.podDisruptionBudget.enabled | bool | `false` |  |
| component.frontend.replicaCount | int | `1` |  |
| component.frontend.resources.requests.ephemeral-storage | string | `"10Gi"` |  |
| component.frontend.service.ports[0].name | string | `"http"` |  |
| component.frontend.service.ports[0].port | int | `5173` |  |
| component.frontend.service.ports[0].protocol | string | `"TCP"` |  |
| component.frontend.service.ports[0].targetPort | int | `5173` |  |
| component.frontend.service.type | string | `"ClusterIP"` |  |
| component.frontend.serviceAccount.create | bool | `true` |  |
| component.frontend.serviceAccount.name | string | `"opik-frontend"` |  |
| component.frontend.throttling | object | `{}` |  |
| component.frontend.upstreamConfig | object | `{}` |  |
| component.python-backend.autoscaling.enabled | bool | `false` |  |
| component.python-backend.backendConfigMap.enabled | bool | `true` |  |
| component.python-backend.enabled | bool | `true` |  |
| component.python-backend.env.OPIK_REVERSE_PROXY_URL | string | `"http://opik-frontend:5173/api"` |  |
| component.python-backend.env.OPIK_URL_OVERRIDE | string | `"http://opik-backend:8080"` |  |
| component.python-backend.env.OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED | bool | `true` |  |
| component.python-backend.env.OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE | string | `"cumulative"` |  |
| component.python-backend.env.OTEL_METRIC_EXPORT_INTERVAL | string | `"60000"` |  |
| component.python-backend.env.OTEL_PROPAGATORS | string | `"tracecontext,baggage"` |  |
| component.python-backend.env.OTEL_SERVICE_NAME | string | `"opik-python-backend"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_ALLOW_NETWORK | string | `"false"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_CPU_SHARES | string | `"512"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS | string | `"3"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_IMAGE_NAME | string | `"opik-sandbox-executor-python"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY | string | `"ghcr.io/comet-ml/opik"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_MEM_LIMIT | string | `"256m"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_PARALLEL_NUM | string | `"5"` |  |
| component.python-backend.env.PYTHON_CODE_EXECUTOR_STRATEGY | string | `"process"` |  |
| component.python-backend.env.REDIS_URL | string | `"redis://:wFSuJX9nDBdCa25sKZG7bh@opik-redis-master:6379/"` |  |
| component.python-backend.envFrom[0].configMapRef.name | string | `"opik-python-backend"` |  |
| component.python-backend.image.pullPolicy | string | `"IfNotPresent"` |  |
| component.python-backend.image.repository | string | `"opik-python-backend"` |  |
| component.python-backend.ingress.annotations | object | `{}` |  |
| component.python-backend.ingress.enabled | bool | `false` |  |
| component.python-backend.ingress.hosts | list | `[]` |  |
| component.python-backend.ingress.ingressClassName | string | `""` |  |
| component.python-backend.ingress.tls.enabled | bool | `false` |  |
| component.python-backend.ingress.tls.hosts | list | `[]` |  |
| component.python-backend.ingress.tls.secretName | string | `""` |  |
| component.python-backend.metrics.enabled | bool | `false` |  |
| component.python-backend.networkPolicy.additionalRules | list | `[]` |  |
| component.python-backend.networkPolicy.annotations | object | `{}` |  |
| component.python-backend.networkPolicy.enabled | bool | `false` |  |
| component.python-backend.podDisruptionBudget.enabled | bool | `false` |  |
| component.python-backend.replicaCount | int | `1` |  |
| component.python-backend.secretRefs | list | `[]` |  |
| component.python-backend.securityContext.privileged | bool | `true` |  |
| component.python-backend.service.ports[0].name | string | `"http"` |  |
| component.python-backend.service.ports[0].port | int | `8000` |  |
| component.python-backend.service.ports[0].protocol | string | `"TCP"` |  |
| component.python-backend.service.ports[0].targetPort | int | `8000` |  |
| component.python-backend.service.type | string | `"ClusterIP"` |  |
| component.python-backend.serviceAccount.create | bool | `true` |  |
| component.python-backend.serviceAccount.name | string | `"opik-python-backend"` |  |
| demoDataJob.enabled | bool | `true` |  |
| fullnameOverride | string | `""` |  |
| global.argocd | bool | `false` |  |
| global.security.allowInsecureImages | bool | `true` |  |
| global.useHelmHooks | bool | `true` |  |
| localFE | bool | `false` |  |
| localFEAddress | string | `"host.minikube.internal:5174"` |  |
| minio.auth.rootPassword | string | `"LESlrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"` |  |
| minio.auth.rootUser | string | `"THAAIOSFODNN7EXAMPLE"` |  |
| minio.config.browserEnabled | bool | `false` |  |
| minio.defaultBuckets | string | `"public:download"` |  |
| minio.enabled | bool | `true` |  |
| minio.fullnameOverride | string | `"opik-minio"` |  |
| minio.image.imagePullPolicy | string | `"IfNotPresent"` |  |
| minio.image.registry | string | `"docker.io"` |  |
| minio.image.repository | string | `"cloudpirates/image-minio"` |  |
| minio.image.tag | string | `"RELEASE.2025-10-15T17-29-55Z-hardened"` |  |
| minio.persistence.enabled | bool | `true` |  |
| minio.persistence.size | string | `"50Gi"` |  |
| minio.replicaCount | int | `1` |  |
| mysql.auth.rootPassword | string | `"opik"` |  |
| mysql.enabled | bool | `true` |  |
| mysql.fullnameOverride | string | `"opik-mysql"` |  |
| mysql.initdbScripts."createdb.sql" | string | `"CREATE DATABASE IF NOT EXISTS opik DEFAULT CHARACTER SET utf8 DEFAULT COLLATE utf8_general_ci;\nCREATE USER IF NOT EXISTS 'opik'@'%' IDENTIFIED BY 'opik';\nGRANT ALL ON `opik`.* TO 'opik'@'%';\nFLUSH PRIVILEGES;"` |  |
| mysql.primary.dataDir | string | `"/bitnami/mysql/data"` |  |
| mysql.primary.persistence.enabled | bool | `true` |  |
| mysql.primary.persistence.size | string | `"20Gi"` |  |
| nameOverride | string | `"opik"` |  |
| nodeSelector | object | `{}` |  |
| redis.architecture | string | `"standalone"` |  |
| redis.auth.enabled | bool | `true` |  |
| redis.auth.password | string | `"wFSuJX9nDBdCa25sKZG7bh"` |  |
| redis.config.content | string | `"# Redis Stack configuration\ndir /data\nmaxmemory 105M\nmaxmemory-policy allkeys-lru\n# Redis Stack modules are automatically loaded by the redis-stack-server image\n"` |  |
| redis.enabled | bool | `true` |  |
| redis.fullnameOverride | string | `"opik-redis-master"` |  |
| redis.image.pullPolicy | string | `"IfNotPresent"` |  |
| redis.image.registry | string | `"docker.io"` |  |
| redis.image.repository | string | `"redis/redis-stack-server"` |  |
| redis.image.tag | string | `"7.2.0-v10"` |  |
| redis.metrics.enabled | bool | `false` |  |
| redis.persistence.enabled | bool | `true` |  |
| redis.persistence.size | string | `"8Gi"` |  |
| redis.resources.limits.memory | string | `"1Gi"` |  |
| redis.resources.requests.cpu | string | `"15m"` |  |
| redis.resources.requests.memory | string | `"105M"` |  |
| registry | string | `"ghcr.io/comet-ml/opik"` |  |
| serviceAccount.annotations | object | `{}` |  |
| serviceAccount.create | bool | `false` |  |
| serviceAccount.name | string | `""` |  |
| standalone | bool | `true` |  |
| tolerations | list | `[]` |  |
| zookeeper.commonLabels."app.kubernetes.io/name" | string | `"zookeeper-opik"` |  |
| zookeeper.enabled | bool | `true` |  |
| zookeeper.extraEnvVars[0].name | string | `"ZK_HEAP_SIZE"` |  |
| zookeeper.extraEnvVars[0].value | string | `"512M"` |  |
| zookeeper.extraEnvVars[1].name | string | `"ZOO_DATA_DIR"` |  |
| zookeeper.extraEnvVars[1].value | string | `"/bitnami/zookeeper/data"` |  |
| zookeeper.fullnameOverride | string | `"opik-zookeeper"` |  |
| zookeeper.headless.publishNotReadyAddresses | bool | `true` |  |
| zookeeper.image.imagePullPolicy | string | `"IfNotPresent"` |  |
| zookeeper.image.registry | string | `"docker.io"` |  |
| zookeeper.image.repository | string | `"zookeeper"` |  |
| zookeeper.image.tag | string | `"3.9.4"` |  |
| zookeeper.persistence.dataDir | string | `"/bitnami/zookeeper/data"` |  |
| zookeeper.persistence.enabled | bool | `true` |  |
| zookeeper.persistence.mountPath | string | `"/bitnami/zookeeper"` |  |
| zookeeper.persistence.size | string | `"50Gi"` |  |
| zookeeper.podDisruptionBudget.enabled | bool | `true` |  |
| zookeeper.replicaCount | int | `1` |  |
| zookeeper.serverIdOffset | int | `1` |  |

----------------------------------------------
Autogenerated from chart metadata using [helm-docs v1.14.2](https://github.com/norwoodj/helm-docs/releases/v1.14.2)
