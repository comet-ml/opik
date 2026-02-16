# OpikAssist - AI-Powered Trace Analyzer

OpikAssist is an AI-powered trace analyzer service that provides conversational analysis of Opik traces using Google's Agent Development Kit (ADK).

## Overview

OpikAssist allows users to have natural language conversations about their traces, asking questions like:
- "What caused this error?"
- "Why is this trace slow?"
- "What's the difference between these two spans?"

The service uses LLMs (OpenAI, Anthropic, or Google) to analyze trace data and provide insights.

## Architecture

- **Framework**: FastAPI (Python 3.13)
- **Agent Framework**: Google ADK
- **Session Storage**: MySQL (via Google ADK's DatabaseSessionService)
- **Streaming**: Server-Sent Events (SSE)
- **Authentication**: Comet session tokens (pass-through to Opik backend's AuthFilter)
- **Frontend Integration**: Frontend calls OpikAssist directly via nginx proxy at `/opik-ai/`

## Configuration

### Environment Variables

#### Required
- `AGENT_OPIK_URL` - Opik backend URL (e.g., `http://backend:8080`)
  - OpikAssist routes LLM calls through the Opik backend's AI proxy at `/v1/private/chat/completions`
  - The proxy uses the end-user's configured provider API key (stored per-workspace in the database)

#### Database
- `SESSION_SERVICE_URI` - MySQL connection string (e.g., `mysql://opik:opik@mysql:3306/opik`)
  - **Note**: Use `mysql://` (mysqlclient driver), not `mysql+pymysql://`

#### Optional
- `PORT` - Server port (default: `8081`)
- `URL_PREFIX` - URL prefix for all endpoints (default: `""`, production: `/opik-assist`)
- `AGENT_MODEL` - LLM model to use (default: `openai/gpt-4.1`)
- `AGENT_REASONING_EFFORT` - Reasoning effort for the model (optional, model-specific)

#### Monitoring (Optional)
- `SENTRY_DSN` - Sentry error monitoring DSN
- `SENTRY_ENVIRONMENT` - Environment name (development/staging/production)
- `SEGMENT_WRITE_KEY` - Segment analytics write key

## API Endpoints

All endpoints are prefixed with `URL_PREFIX` (default: `/opik-ai` in production):

- `GET /healthz` - Health check endpoint
- `GET /trace-analyzer/session/{trace_id}` - Get conversation history
- `POST /trace-analyzer/session/{trace_id}` - Start/continue conversation (SSE streaming)
- `DELETE /trace-analyzer/session/{trace_id}` - Delete conversation session
- `PUT /trace-analyzer/session/{trace_id}/feedback` - Submit feedback
- `DELETE /trace-analyzer/session/{trace_id}/feedback` - Delete feedback

## Local Development

### Using Docker Compose

1. Start the service:
```bash
docker compose --profile opik-ai-backend up
```

2. The service will be available at `http://localhost:8081`

3. Configure your LLM provider API key in the Opik UI (Settings > LLM Providers)
   - OpikAssist uses the end-user's configured provider API key via the Opik backend proxy
   - No API key needs to be configured on the OpikAssist service itself

### Backend Configuration

The Opik backend only needs the feature toggle enabled:

```bash
export TOGGLE_OPIK_AI_ENABLED="true"
```

**Note**: The backend doesn't call OpikAssist directly. The frontend makes requests to OpikAssist via the nginx proxy at `/opik-ai/`. In local development, the frontend uses `VITE_BASE_OPIK_AI_URL` to configure the OpikAssist endpoint.

## Database Schema

Google ADK's `DatabaseSessionService` automatically creates the following tables in MySQL:
- `sessions` - Session metadata
- `user_state` - User-specific state data
- `app_state` - Application-level state
- `raw_events` - Complete event history

These tables are created automatically on first connection.

## Known Limitations

1. **Model Configuration**: Uses `AGENT_MODEL` env var (default: `openai/gpt-4.1`) - the model must be supported by the user's configured provider
2. **Local Auth**: In standalone mode (no session token), uses `"default"` user ID
3. **ADK Schema Migrations**: Not automatic - requires manual intervention when upgrading ADK
4. **Provider API Keys**: Requires users to configure their LLM provider API keys in the Opik UI (Settings > LLM Providers)

### Schema Migrations

Google ADK does not automatically migrate database schemas between versions. When upgrading ADK:

1. Check the [ADK release notes](https://github.com/google/adk-python/releases) for schema changes
2. If schema changes are required, run manual migration:
```bash
adk migrate session \
  --source_db_url=mysql://opik:opik@mysql:3306/opik \
  --dest_db_url=mysql://opik:opik@mysql:3306/opik_v2
```
3. Update `SESSION_SERVICE_URI` to point to the new database

**Important**: Use `mysql://` (not `mysql+pymysql://`) as ADK expects the mysqlclient driver.

**Current ADK version**: See `pyproject.toml` (pinned to avoid unexpected schema changes)

## Dependencies

Key dependencies (see `pyproject.toml` for full list):
- `google-adk>=1.9.0` - Agent Development Kit
- `litellm>=1.55.1` - LLM abstraction layer
- `mysqlclient>=2.2.7` - MySQL database driver
- `opik>=1.9.89` - Opik SDK
- `sentry-sdk[fastapi]>=2.34.1` - Error monitoring
- `newrelic>=10.15.0` - APM monitoring
- `segment-analytics-python>=2.0.0` - Analytics

## Deployment

### Docker Compose (Local/Development)

See "Local Development" section above.

### Kubernetes/Helm (Production)

Helm chart configuration is not included in this initial integration. For production deployment:

1. Consider using MySQL advisory locks for migration coordination across multiple pods
2. Configure proper resource limits (CPU/Memory)
3. Set up health checks and readiness probes
4. Configure external secrets management for API keys

## Troubleshooting

### Service won't start
- Check that at least one LLM API key is set
- Verify MySQL is accessible and credentials are correct
- Check logs for authentication errors

### Sessions not persisting
- Verify `SESSION_SERVICE_URI` is set correctly
- Check MySQL connection and permissions
- Ensure ADK tables were created (check MySQL for `sessions`, `user_state`, etc.)

### SSE streaming not working
- Ensure nginx configuration has `proxy_buffering off`
- Check that `proxy_read_timeout` is set high enough (300s recommended)
- Verify `Connection` header is set to `""` (empty string for keep-alive)
