# Opik JavaScript/TypeScript SDK

## Installation

You can install the `opik` package using your favorite package manager.

```bash
npm install opik
```

## Usage

```typescript
import { Opik } from "opik";

// Create a new Opik client with your configuration
const client = new Opik({
  apiKey: "<your-api-key>",
  host: "https://www.comet.com/opik/api",
  projectName: "<your-project-name>",
  workspaceName: "<your-workspace-name>",
});

// Log 10 traces
for (let i = 0; i < 10; i++) {
  const someTrace = client.trace({
    name: `Trace ${i}`,
  });

  // For each trace, log 10 spans
  for (let j = 0; j < 10; j++) {
    const someSpan = someTrace.span({
      name: `Span ${i}-${j}`,
      type: "llm",
    });

    // Mark the span as ended
    someSpan.end();
  }

  // Mark the trace as ended
  someTrace.end();
}

// Flush the client to send all traces and spans
await client.flush();
```

## Contributing

Contributions are welcome! If you have any suggestions or improvements, please feel free to open an [issue](https://github.com/comet-ml/opik/issues) or submit a [pull request](https://github.com/comet-ml/opik/pulls).

## License

This project is licensed under the [Apache License 2.0](https://github.com/comet-ml/opik/blob/main/LICENSE).
