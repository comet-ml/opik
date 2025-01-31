import { Opik } from "@opik";

describe("OpikApiClient", () => {
  let client: Opik;

  beforeAll(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript-test",
    });
  });

  it("should log a trace and a span", async () => {
    const someTrace = client.trace({
      name: "test",
    });

    const someSpan = someTrace.span({
      name: "test span",
      type: "llm",
    });

    someSpan.end();
    someTrace.end();

    await client.flush();
  });
});
