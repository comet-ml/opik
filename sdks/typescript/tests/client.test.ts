import { Opik } from "@opik";

const waitForDelay = (ms: number) => {
  const promise = new Promise((resolve) => setTimeout(resolve, ms));
  vi.advanceTimersByTime(ms);
  return promise;
};

const logTraceAndSpan = async ({
  client,
  delay,
  spanAmount = 5,
  traceAmount = 5,
}: {
  client: Opik;
  delay?: number;
  spanAmount?: number;
  traceAmount?: number;
}) => {
  for (let i = 0; i < traceAmount; i++) {
    const someTrace = client.trace({
      name: `test-${i}`,
    });

    for (let j = 0; j < spanAmount; j++) {
      const someSpan = someTrace.span({
        name: `test-${i}-span-${j}`,
        type: "llm",
      });

      if (delay) {
        await waitForDelay(delay);
      }

      someSpan.end();
    }

    someTrace.end();
  }

  await client.flush();
};

describe("OpikApiClient", () => {
  let client: Opik;

  beforeAll(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    vi.useFakeTimers();
  });

  afterAll(() => {
    vi.useRealTimers();
  });

  it("should log traces and spans in batches", async () => {
    await logTraceAndSpan({ client });
  });

  it("should log traces and spans - one call per trace, batch per span (<300ms)", async () => {
    await logTraceAndSpan({ client, delay: 100 });
  });

  it("should log traces and spans - one call per each entity (>300ms)", async () => {
    await logTraceAndSpan({ client, delay: 100 });
  });
});
