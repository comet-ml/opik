# Opik frontend

If you would like to contribute to the Opik frontend, please refer to the [Contribution guide](../../CONTRIBUTING.md).

## Project Structure

```
src/
├── api/              # API client and endpoint definitions
├── constants/        # Application-wide constants
├── contexts/         # React context providers
├── hooks/            # Shared custom hooks
├── lib/              # Utility libraries
├── plugins/          # Plugin integrations (e.g. Comet)
├── store/            # Zustand stores
├── types/            # Shared TypeScript types
│
├── ui/               # Base UI components (shadcn/ui + Radix)
├── shared/           # Shared business components (no API calls)
│
├── v1/               # Opik 1 — feature-organized navigation
│   ├── layout/       # App shell, sidebar, header
│   ├── pages/        # Route-level page components
│   └── pages-shared/ # Components shared across v1 pages
│
├── v2/               # Opik 2 — project-first navigation
│   ├── layout/       # App shell, sidebar, header
│   ├── pages/        # Route-level page components
│   └── pages-shared/ # Components shared across v2 pages
│
├── router.tsx        # Route definitions
└── index.tsx         # App entry point
```

### Import rules
- `ui → shared` (one-way only)
- `ui → shared → v1/pages-shared → v1/pages` (one-way only)
- `ui → shared → v2/pages-shared → v2/pages` (one-way only)
- v1/ CANNOT import from v2/; v2/ CANNOT import from v1/
- Validate with: `npm run deps:validate`

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
