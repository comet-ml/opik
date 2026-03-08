# Opik Agentica Integration

[![npm version](https://img.shields.io/npm/v/opik-agentica.svg)](https://www.npmjs.com/package/opik-agentica)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Integrate [Opik](https://www.comet.com/docs/opik/) observability with [Symbolica Agentica](https://www.symbolica.ai/).

## Installation

```bash
npm install opik-agentica @symbolica/agentica
```

## Quick Start

```typescript
import * as agentica from "@symbolica/agentica";
import { trackAgentica } from "opik-agentica";

const trackedAgentica = trackAgentica(agentica, {
  traceMetadata: {
    tags: ["agentica", "production"],
    environment: "prod",
  },
});

const agent = await trackedAgentica.spawn({
  premise: "You are a helpful assistant",
});

const answer = await agent.call<string>("Summarize the user's request");
console.log(answer);

await trackedAgentica.flush();
```

## Notes

- The integration wraps Agentica call paths (`spawn`, `agentic`, `call`, and transformation variants).
- Usage tokens are normalized into Opik usage keys (`prompt_tokens`, `completion_tokens`, `total_tokens`) when available.
- Always call `flush()` before process exit in scripts and tests.

