import { Opik } from "@opik";

describe("OpikApiClient", () => {
  let client: Opik;

  beforeAll(() => {
    client = new Opik();
  });

  it("should log a trace and a span", async () => {
    const someTrace = await client.trace({
      name: "test",
    });

    const someSpan = await someTrace.span({
      name: "test span",
      type: "llm",
    });

    await someSpan.end();
    await someTrace.end();
  });
});
