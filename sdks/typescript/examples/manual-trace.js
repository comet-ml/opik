import { Opik } from "opik";

const client = new Opik();
const someTrace = await client.trace({
  name: "test123",
  startTime: new Date(),
});

const someSpan = await someTrace.span({
  name: "test123 span",
  type: "llm",
  startTime: new Date(),
});

await someSpan.end();
await someTrace.end();
