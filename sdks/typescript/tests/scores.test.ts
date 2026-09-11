import { Opik } from "opik";
import { MockInstance } from "vitest";
import { mockAPIFunction } from "./mockUtils";

describe("Feedback scores", () => {
  let client: Opik;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateSpansSpy: MockInstance<typeof client.api.spans.updateSpan>;
  let createSpansFeedbackScoresSpy: MockInstance<
    typeof client.api.spans.scoreBatchOfSpans
  >;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let updateTracesSpy: MockInstance<typeof client.api.traces.updateTrace>;
  let createTracesFeedbackScoresSpy: MockInstance<
    typeof client.api.traces.scoreBatchOfTraces
  >;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    updateSpansSpy = vi
      .spyOn(client.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction);

    createSpansFeedbackScoresSpy = vi
      .spyOn(client.api.spans, "scoreBatchOfSpans")
      .mockImplementation(mockAPIFunction);

    createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    updateTracesSpy = vi
      .spyOn(client.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction);

    createTracesFeedbackScoresSpy = vi
      .spyOn(client.api.traces, "scoreBatchOfTraces")
      .mockImplementation(mockAPIFunction);

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();

    createSpansSpy.mockRestore();
    createTracesSpy.mockRestore();
    updateSpansSpy.mockRestore();
    updateTracesSpy.mockRestore();
    createSpansFeedbackScoresSpy.mockRestore();
    createTracesFeedbackScoresSpy.mockRestore();
  });

  it("add 2 scores to a trace (batching)", async () => {
    const trace = client.trace({ name: "test" });
    const span = trace.span({ name: "test-span", type: "llm" });

    span.score({
      name: "test-span",
      value: 1,
    });
    span.score({
      name: "test-span-2",
      value: 2,
    });

    span.end();

    trace.score({
      name: "test",
      value: 1,
    });
    trace.score({
      name: "test-2",
      categoryName: "test-category",
      reason: "test-reason",
      value: 2,
    });

    trace.end();
    await client.flush();

    expect(createTracesSpy).toHaveBeenCalledTimes(1);
    expect(updateTracesSpy).toHaveBeenCalledTimes(0);
    expect(createSpansSpy).toHaveBeenCalledTimes(1);
    expect(updateSpansSpy).toHaveBeenCalledTimes(0);
    expect(createTracesFeedbackScoresSpy).toHaveBeenCalledTimes(1);
    expect(createSpansFeedbackScoresSpy).toHaveBeenCalledTimes(1);
  });
});
