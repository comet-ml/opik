import { OpikClient } from "@opik";

describe("OpikApiClient", () => {
  let client: OpikClient;

  beforeAll(() => {
    client = new OpikClient();
  });

  it("should log a trace and a span", async () => {
    const someTrace = await client.trace({
      name: "test",
      startTime: new Date(),
    });

    const someSpan = await someTrace.span({
      name: "test span",
      startTime: new Date(),
      type: "llm",
    });

    await someSpan.end();
    await someTrace.end();
  });
});
