import { beforeEach, describe, expect, it, vi } from "vitest";
import { trackExa } from "../src/index";
import type { Opik } from "opik";

vi.mock("opik", () => ({
  Opik: vi.fn(),
  OpikSpanType: {
    Tool: "tool",
  },
}));

type MockSpan = {
  update: ReturnType<typeof vi.fn>;
  end: ReturnType<typeof vi.fn>;
};

type MockTrace = {
  span: ReturnType<typeof vi.fn>;
  update: ReturnType<typeof vi.fn>;
  end: ReturnType<typeof vi.fn>;
};

const createMockSpan = (): MockSpan => ({
  update: vi.fn(),
  end: vi.fn(),
});

const createMockTrace = (span: MockSpan): MockTrace => ({
  span: vi.fn().mockReturnValue(span),
  update: vi.fn(),
  end: vi.fn(),
});

const createMockOpikClient = (trace: MockTrace): Opik =>
  ({
    trace: vi.fn().mockReturnValue(trace),
    flush: vi.fn().mockResolvedValue(undefined),
  }) as unknown as Opik;

describe("trackExa", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("tracks exa.search calls as tool spans with search metadata", async () => {
    const span = createMockSpan();
    const trace = createMockTrace(span);
    const opikClient = createMockOpikClient(trace);

    const exaClient = {
      search: vi.fn().mockResolvedValue({
        results: [{ title: "A" }, { title: "B" }],
      }),
    };

    const tracked = trackExa(exaClient, {
      client: opikClient,
      traceMetadata: { tags: ["exa"], env: "test" },
    });

    const output = await tracked.search({ query: "ai agents" });

    expect(output.results).toHaveLength(2);
    expect(opikClient.trace).toHaveBeenCalledTimes(1);
    expect(trace.span).toHaveBeenCalledWith(
      expect.objectContaining({
        type: "tool",
        name: "exa.search",
      })
    );
    expect(span.update).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.objectContaining({
          "opik.kind": "search",
          "opik.provider": "exa",
          "opik.operation": "search",
          "opik.result_count": 2,
          env: "test",
        }),
      })
    );
  });

  it("adds flush helper and supports camelCase methods", async () => {
    const span = createMockSpan();
    const trace = createMockTrace(span);
    const opikClient = createMockOpikClient(trace);

    const exaClient = {
      searchAndContents: vi.fn().mockResolvedValue({ results: [{ text: "x" }] }),
    };

    const tracked = trackExa(exaClient, { client: opikClient });
    await tracked.searchAndContents("state of ai");
    await tracked.flush();

    expect(span.update).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.objectContaining({
          "opik.operation": "search_and_contents",
        }),
      })
    );
    expect(opikClient.flush).toHaveBeenCalledTimes(1);
  });
});
