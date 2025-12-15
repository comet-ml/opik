# Opik frontend

If you would like to contribute to the Opik frontend, please refer to the [Contribution guide](./CONTRIBUTING.md).

## Environment Variables

### Frontend (Runtime)

The following environment variables can be used to configure the Nginx container at runtime (see `apps/opik-frontend/patch-nginx.conf.sh`):

| Variable | Default | Description |
|----------|---------|-------------|
| `NGINX_PID` | `/run/nginx.pid` | Path to the Nginx PID file. |
| `OTEL_COLLECTOR_HOST` | `otel-collector` | Hostname of the OpenTelemetry collector. |
| `OTEL_COLLECTOR_PORT` | `4317` | Port of the OpenTelemetry collector. |
| `OTEL_TRACES_EXPORTER` | `otlp` | Exporter type for OpenTelemetry. |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://${OTEL_COLLECTOR_HOST}:${OTEL_COLLECTOR_PORT}` | Full endpoint URL for OTLP traces. |
| `NGINX_PORT` | `8080` | Port Nginx listens on. |
| `OTEL_TRACE` | `off` | Enable (`on`) or disable (`off`) OpenTelemetry tracing. |
| `NGINX_EXTRA_ACCESS_LOG` | _(empty)_ | Additional access log configuration. e.g. `access_log syslog:server=otel-collector:5140 logger-json;` |
| `NGINX_EXTRA_ERROR_LOG` | _(empty)_ | Additional error log configuration. e.g. `error_log syslog:server=otel-collector:5140 error;` |

### Backend (Build/Runtime)

The following variables are defined in the backend Dockerfile (`apps/opik-backend/Dockerfile`):

| Variable | Default | Description |
|----------|---------|-------------|
| `OPIK_VERSION` | _(Required)_ | Version of the Opik application (Build ARG & ENV). |
| `STORE_PASSWORD` | `changeit` | Truststore password for RDS certificates (Build ARG). |
