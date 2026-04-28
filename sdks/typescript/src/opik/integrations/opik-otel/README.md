# Opik OpenTelemetry Integration

[![npm version](https://img.shields.io/npm/v/opik-otel.svg)](https://www.npmjs.com/package/opik-otel)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Bridge [OpenTelemetry](https://opentelemetry.io/) traces produced by your services into [Opik](https://www.comet.com/docs/opik/) so spans land under the correct Opik trace and parent span.

## Features

- 🔗 **`attachToParent`** — read `opik_trace_id` / `opik_parent_span_id` HTTP headers and stamp the matching attributes on an OpenTelemetry boundary span
- 🌳 **`OpikSpanProcessor`** — propagate Opik IDs down an entire OTel subtree so descendants of the boundary span are linked to the same Opik trace and chain of parents
- 🧰 **Header constants & types** — `OPIK_TRACE_ID`, `OPIK_SPAN_ID`, `OPIK_PARENT_SPAN_ID`, `OpikDistributedTraceAttributes`

## Installation

```bash
# npm
npm install opik-otel

# yarn
yarn add opik-otel

# pnpm
pnpm add opik-otel
```

### Requirements

- Node.js ≥ 18
- `@opentelemetry/api` ≥ 1.9
- `@opentelemetry/sdk-trace-base` ≥ 1.30 (or v2)
- Opik SDK (`opik` peer dependency)

## Quick Start

```typescript
import { trace } from "@opentelemetry/api";
import {
  BasicTracerProvider,
  BatchSpanProcessor,
} from "@opentelemetry/sdk-trace-base";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-http";
import { attachToParent, OpikSpanProcessor } from "opik-otel";

const provider = new BasicTracerProvider();
provider.addSpanProcessor(new BatchSpanProcessor(new OTLPTraceExporter()));
provider.addSpanProcessor(new OpikSpanProcessor());
provider.register();

const tracer = trace.getTracer("my-service");

// On every incoming request:
const span = tracer.startSpan("server-span");
attachToParent(span, request.headers);
// ... handle the request; descendant spans inherit Opik IDs automatically
span.end();
```

See the Opik documentation on [distributed traces with OpenTelemetry](https://www.comet.com/docs/opik/tracing/advanced/log_distributed_traces#distributed-traces-with-a-remote-service-using-opentelemetry) for the full client/server pattern.