# OTEL GenAI Ingestion Gaps / Changes (Current Slice)

## Scope
- Track OpenTelemetry GenAI trace ingestion behavior implemented and validated in this pass.
- Exclusions for this slice: OpenInference and Langfuse parity workstreams.
- We are intentionally not adding OTEL metrics persistence in this slice.

## Fixed in this slice

1. Thread ID precedence is deterministic
- Both `thread_id` and `gen_ai.conversation.id` map to `thread_id`.
- Previously, mapping relied on attribute iteration order and could flip precedence.
- Fix: deterministic handling keeps explicit `thread_id` precedence so `thread_id` always wins when present.

2. Malformed usage payloads no longer abort a whole span
- A malformed `gen_ai.usage.*` JSON string could throw and fail trace ingestion.
- Fix: `OpenTelemetryMapper` now catches malformed usage extraction exceptions and skips that usage key only, preserving mapping for remaining attributes.

3. OpenAI compatibility keys now mapped (deprecated namespace variants)
- Added explicit metadata mappings for:
  - `gen_ai.openai.request.response_format`
  - `gen_ai.openai.request.seed`
  - `gen_ai.openai.request.service_tier`
  - `gen_ai.openai.response.service_tier`
  - `gen_ai.openai.response.system_fingerprint`

## Remaining trace-side gaps (intentional for this slice)

1. OTEL metrics endpoints are still not implemented
- `/v1/private/otel/v1/metrics` responds with HTTP `501` and a JSON `ErrorMessage` for both `application/x-protobuf` and `application/json`.
- This is explicit by design: the endpoint contract is clear, but persistence and DAO/query wiring for metric telemetry are not yet in scope.

## Validation done in this slice

- `OpenTelemetryMapperTest`
  - Thread-id precedence
  - GenAI mapping behavior
  - Deprecated OpenAI namespaced keys
  - Malformed usage does not block input field mapping
- `OpenTelemetryResourceTest`
  - Protobuf + JSON trace ingestion with auth/error matrix
  - Protobuf metrics matrix (both content types + auth path) with explicit `501`
  - Trace request with malformed `gen_ai.usage.input_tokens` payload verifies 200 + persisted span
- `OpenTelemetryMappingUtilsTest`
  - `extractUsageField` keeps previous contract (throws on malformed usage JSON at utility level; caller now isolates that failure)

## External references used for gap review

- OpenTelemetry GenAI semantic conventions:
  - https://github.com/open-telemetry/opentelemetry-specification/blob/main/semantic_conventions/trace/gen-ai.md
  - https://opentelemetry.io/docs/specs/semconv/ai/
  - https://opentelemetry.io/docs/specs/semconv/ai/semantic_conventions/
- OpenTelemetry metrics context:
  - https://opentelemetry.io/docs/specs/otel/metrics/
- OpenAI API compatibility context:
  - https://platform.openai.com/docs/api-reference/chat/create
  - https://platform.openai.com/docs/api-reference/completions/create
- Anthropic API context:
  - https://docs.anthropic.com/en/api/messages

## Notes
- This pass is trace-first only. Metrics and parity workstreams are intentionally deferred for separate slices.
