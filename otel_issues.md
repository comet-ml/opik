# OTel Integration Issues Log

## Scope
- This document tracks OTEL mapping issues reviewed/fixed for OpenTelemetry GenAI span ingestion.
- Explicitly excluded for this pass: OpenInference and Langfuse parity workstreams.

## Fixed (in this slice)

1. Thread ID precedence should be deterministic
- Issue: `thread_id` and `gen_ai.conversation.id` were both mapped through `THREAD_ID`, with `THREAD_ID` assignment being order-dependent.
- Impact: If `gen_ai.conversation.id` appeared before `thread_id`, precedence could flip.
- Fix: `OpenTelemetryMapper` now detects explicit `thread_id` presence up front and always lets that attribute win over conversation ID.

2. Malformed usage JSON should not fail whole span mapping
- Issue: invalid JSON under `gen_ai.usage.*` propagated `BadRequestException` from usage extraction.
- Impact: A single bad usage payload could abort ingestion for the entire span and fail trace logging.
- Fix: usage JSON parse failures are now logged and skipped, allowing span mapping to continue.

3. OpenAI deprecated compatibility keys now mapped
- Added metadata mappings for deprecated OpenAI namespace keys:
  - `gen_ai.openai.request.response_format`
  - `gen_ai.openai.request.seed`
  - `gen_ai.openai.request.service_tier`
  - `gen_ai.openai.response.service_tier`
  - `gen_ai.openai.response.system_fingerprint`
- These are mapped as metadata to preserve compatibility with older OpenAI-compatible instrumentations (v1.36-era keys).

## Follow-ups / remaining gaps

1. Dedicated OTEL metrics ingestion is not yet implemented
- `gen_ai.client.operation.duration`
- `gen_ai.client.token.usage`
- Status: `/v1/private/otel/v1/metrics` is now explicitly implemented and returns `501 Not Implemented` for both protobuf and JSON payloads, so behavior is now explicit instead of returning 404.
- Action: add a dedicated OTEL metrics ingestion pipeline and first-class metric persistence before marking this as fully covered.

2. OpenInference / Langfuse parity
- Excluded from this fix cycle per request.

## Validation run
- `mvn -f apps/opik-backend/pom.xml -Dtest=OpenTelemetryMapperTest,OpenTelemetryMappingUtilsTest test`
- `mvn -f apps/opik-backend/pom.xml -Dtest=OpenTelemetryResourceTest test`
- `mvn -f apps/opik-backend/pom.xml spotless:check`
