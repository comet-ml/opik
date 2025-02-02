import { Opik } from "opik";

const client = new Opik();
const someTrace = await client.trace({
  name: "test123",
});

const someSpan = await someTrace.span({
  name: "test123 span",
  type: "llm",
});

await someSpan.end();
await someTrace.end();
