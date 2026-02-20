import { Opik } from "opik";
import { OpikExporter } from "../src/exporter";
import { mockAPIFunction } from "./mockUtils";

/**
 * Tests for OpenTelemetry v1 compatibility.
 *
 * @vercel/otel uses OpenTelemetry v1 which provides spans with
 * `instrumentationLibrary` instead of `instrumentationScope`.
 * These tests verify that OpikExporter handles both cases.
 *
 * See: https://github.com/comet-ml/opik/issues/3361
 */

function createMockOtelSpan(overrides: Record<string, unknown> = {}) {
  const startTime: [number, number] = [1700000000, 0];
  const endTime: [number, number] = [1700000001, 0];
  return {
    name: "test-span",
    kind: 0,
    spanContext: () => ({
      traceId: "abc123",
      spanId: "span1",
      traceFlags: 1,
    }),
    parentSpanContext: undefined,
    startTime,
    endTime,
    status: { code: 0 },
    attributes: {
      "ai.prompt": JSON.stringify({ prompt: "Hello" }),
      "ai.response.text": "World",
    },
    links: [],
    events: [],
    duration: [1, 0] as [number, number],
    ended: true,
    resource: { attributes: {} },
    droppedAttributesCount: 0,
    droppedEventsCount: 0,
    droppedLinksCount: 0,
    ...overrides,
  };
}

describe("OpikExporter - OpenTelemetry v1 compatibility", () => {
  let client: Opik;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });

    vi.spyOn(client.api.spans, "createSpans").mockImplementation(
      mockAPIFunction as never
    );
    vi.spyOn(client.api.spans, "updateSpan").mockImplementation(
      mockAPIFunction as never
    );
    vi.spyOn(client.api.traces, "createTraces").mockImplementation(
      mockAPIFunction as never
    );
    vi.spyOn(client.api.traces, "updateTrace").mockImplementation(
      mockAPIFunction as never
    );
  });

  it("should handle spans with instrumentationScope (OTel v2)", async () => {
    const exporter = new OpikExporter({ client });

    const mockSpan = createMockOtelSpan({
      instrumentationScope: { name: "ai", version: "1.0.0" },
    });

    const result = await new Promise<{ code: number }>((resolve) => {
      exporter.export([mockSpan] as never, resolve);
    });

    expect(result.code).toBe(0);
  });

  it("should handle spans with instrumentationLibrary (OTel v1 / @vercel/otel)", async () => {
    const exporter = new OpikExporter({ client });

    // OTel v1 spans have instrumentationLibrary instead of instrumentationScope
    const mockSpan = createMockOtelSpan({
      instrumentationLibrary: { name: "ai", version: "1.0.0" },
      instrumentationScope: undefined,
    });

    const result = await new Promise<{ code: number }>((resolve) => {
      exporter.export([mockSpan] as never, resolve);
    });

    expect(result.code).toBe(0);
  });

  it("should filter out non-AI spans regardless of OTel version", async () => {
    const exporter = new OpikExporter({ client });
    const createTracesSpy = vi.spyOn(client.api.traces, "createTraces");

    // OTel v1 span with non-AI instrumentation library
    const nonAiSpan = createMockOtelSpan({
      instrumentationLibrary: { name: "http", version: "1.0.0" },
      instrumentationScope: undefined,
    });

    const result = await new Promise<{ code: number }>((resolve) => {
      exporter.export([nonAiSpan] as never, resolve);
    });

    expect(result.code).toBe(0);
    // No traces should be created for non-AI spans
    expect(createTracesSpy).not.toHaveBeenCalled();
  });

  it("should not crash when both instrumentationScope and instrumentationLibrary are undefined", async () => {
    const exporter = new OpikExporter({ client });
    const createTracesSpy = vi.spyOn(client.api.traces, "createTraces");

    const spanWithNeither = createMockOtelSpan({
      instrumentationScope: undefined,
      instrumentationLibrary: undefined,
    });

    const result = await new Promise<{ code: number }>((resolve) => {
      exporter.export([spanWithNeither] as never, resolve);
    });

    expect(result.code).toBe(0);
    // Should be filtered out since name is undefined, not "ai"
    expect(createTracesSpy).not.toHaveBeenCalled();
  });
});
