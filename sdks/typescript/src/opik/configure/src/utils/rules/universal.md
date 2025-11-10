# Opik TypeScript SDK - Universal Rules

## API Keys and Configuration

Never hallucinate an API key. Always use environment variables for
configuration:

```typescript
// Use environment variables
export OPIK_API_KEY="your-api-key"
export OPIK_URL_OVERRIDE="https://www.comet.com/opik/api"  // For Opik Cloud
export OPIK_URL_OVERRIDE="http://localhost:5173/api"       // For local
export OPIK_PROJECT_NAME="your-project-name"
export OPIK_WORKSPACE="your-workspace-name"
```

Or pass configuration to the Opik client constructor:

```typescript
import { Opik } from 'opik';

const client = new Opik({
  apiKey: '<your-api-key>',
  apiUrl: 'https://www.comet.com/opik/api',
  projectName: '<your-project-name>',
  workspaceName: '<your-workspace-name>',
});
```

## Tracing Pattern

Always use the trace → span pattern for logging operations:

```typescript
// Create a trace for the high-level operation
const trace = client.trace({
  name: 'Operation Name',
  input: { prompt: 'User input' },
  output: { response: 'System output' },
});

// Create spans for sub-operations
const span = trace.span({
  name: 'Sub-operation',
  type: 'llm',
  input: { prompt: 'Sub-operation input' },
  output: { response: 'Sub-operation output' },
});

// Always end spans and traces
span.end();
trace.end();
```

## Flushing Data

Always call `flush()` at the end of operations to ensure data is sent:

```typescript
// After all traces and spans are created
await client.flush();
```

This is critical for short-lived scripts, serverless functions, and CLI tools.

## Available Integrations

Opik provides integrations with:

- **LangChain (JS/TS)** - Trace chains, agents, tools, and retrievers
- **OpenAI (JS/TS)** - Trace OpenAI SDK calls
- **Vercel AI SDK** - Monitor AI-powered applications
- **Cloudflare Workers AI** - Trace Cloudflare Workers

Always check the integrations documentation before implementing custom tracing.

## Naming Conventions

Before creating any new event or property names, consult existing naming
conventions. Consistency is essential:

- Use descriptive names for traces and spans
- Follow camelCase for TypeScript properties
- Use clear, descriptive names that indicate the operation being performed
- Maintain consistency with existing patterns in the codebase

Changes to existing event and property names may break reporting and distort
data.
