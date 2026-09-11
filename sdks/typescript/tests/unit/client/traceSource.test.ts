import { MockInstance } from "vitest";
import { Opik } from "opik";
import { mockAPIFunction } from "../../mockUtils";

describe("trace source auto-set", () => {
  let client: Opik;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;

  beforeEach(() => {
    client = new Opik();

    createTracesSpy = vi.spyOn(
      client.api.traces,
      "createTraces"
    ).mockImplementation(mockAPIFunction);

    vi.spyOn(client.api.spans, "createSpans").mockImplementation(mockAPIFunction);
    vi.spyOn(client.api.traces, "updateTrace").mockImplementation(mockAPIFunction);
    vi.spyOn(client.api.spans, "updateSpan").mockImplementation(mockAPIFunction);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("client.trace() sets source", () => {
    it("should default source to 'sdk'", () => {
      const trace = client.trace({ name: "test-trace" });

      expect(trace.data.source).toBe("sdk");
    });

    it("should pass source='sdk' to the batch queue", async () => {
      client.trace({ name: "test-trace" });
      await client.traceBatchQueue.flush();

      expect(createTracesSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          traces: expect.arrayContaining([
            expect.objectContaining({ source: "sdk" }),
          ]),
        }),
        expect.anything()
      );
    });

    it("should allow overriding source to 'experiment'", () => {
      const trace = client.trace({
        name: "eval-trace",
        source: "experiment",
      });

      expect(trace.data.source).toBe("experiment");
    });

  });

  describe("span inherits source from trace", () => {
    it("should propagate source='sdk' to child spans", () => {
      const trace = client.trace({ name: "test-trace" });
      const span = trace.span({ name: "test-span" });

      expect(span.data.source).toBe("sdk");
    });

    it("should propagate source='experiment' to child spans", () => {
      const trace = client.trace({
        name: "eval-trace",
        source: "experiment",
      });
      const span = trace.span({ name: "test-span" });

      expect(span.data.source).toBe("experiment");
    });

    it("should propagate source through nested child spans", () => {
      const trace = client.trace({
        name: "eval-trace",
        source: "experiment",
      });
      const parentSpan = trace.span({ name: "parent-span" });
      const childSpan = parentSpan.span({ name: "child-span" });

      expect(childSpan.data.source).toBe("experiment");
    });

    it("should pass source to span batch queue", async () => {
      const createSpansSpy = vi.spyOn(
        client.api.spans,
        "createSpans"
      ).mockImplementation(mockAPIFunction);

      const trace = client.trace({
        name: "test-trace",
        source: "experiment",
      });
      trace.span({ name: "test-span" });
      await client.spanBatchQueue.flush();

      expect(createSpansSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          spans: expect.arrayContaining([
            expect.objectContaining({ source: "experiment" }),
          ]),
        }),
        expect.anything()
      );
    });
  });

  describe("source not sent in updates", () => {
    it("trace.update() should not change source", () => {
      const trace = client.trace({ name: "test-trace" });
      trace.update({ name: "updated-name" });

      expect(trace.data.source).toBe("sdk");
    });

    it("span.update() should not change source", () => {
      const trace = client.trace({ name: "test-trace" });
      const span = trace.span({ name: "test-span" });
      span.update({ name: "updated-span" });

      expect(span.data.source).toBe("sdk");
    });
  });
});
