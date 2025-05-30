import { Opik } from "opik";

const client = new Opik({
  headers: {
    headerName: "header-value",
  },
});

for (let i = 0; i < 10; i++) {
  const someTrace = client.trace({
    name: `Trace ${i}`,
  });

  for (let j = 0; j < 10; j++) {
    const someSpan = someTrace.span({
      name: `Span ${i}-${j}`,
      type: "llm",
    });

    someSpan.end();
  }

  someTrace.end();
}

await client.flush();
