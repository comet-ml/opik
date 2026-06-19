import { SpanStatusCode } from "@opentelemetry/api";
import { Opik } from "opik";
import { MockInstance } from "vitest";
import { OpikExporter } from "../src/exporter";
import { mockAPIFunction } from "./mockUtils";

/**
 * Tests for AI SDK v7 / OpenTelemetry GenAI spans — the convention Vercel eve
 * emits. The installed AI SDK is v6 (`ai.*` scope, covered by ai-sdk.test.ts),
 * so real v7 spans can't be produced here; instead we feed the exporter spans
 * built from attributes captured verbatim from a real eve + Anthropic turn.
 *
 * Being deterministic, these verify the core business logic exactly: expected
 * input, output, token usage, error info, span types, hierarchy, and the
 * create-only (upsert, never update) contract.
 */

// ─────────────────────────────── Fixtures ───────────────────────────────
// eve emits no OTel parent links — spans carry only a trace id and a time
// window, and the exporter reconstructs the hierarchy from time containment.

type SpanSpec = {
  traceId: string;
  spanId: string;
  startMs: number;
  endMs: number;
  error?: { type: string; message: string };
};

const hrTime = (ms: number): [number, number] => [0, ms * 1e6];

function makeSpan(
  scope: string,
  name: string,
  attributes: Record<string, unknown>,
  { traceId, spanId, startMs, endMs, error }: SpanSpec
) {
  return {
    name,
    kind: 0,
    spanContext: () => ({ traceId, spanId, traceFlags: 1 }),
    parentSpanContext: undefined,
    startTime: hrTime(startMs),
    endTime: hrTime(endMs),
    status: error
      ? { code: SpanStatusCode.ERROR, message: error.message }
      : { code: SpanStatusCode.UNSET },
    attributes,
    links: [],
    events: error
      ? [
          {
            name: "exception",
            attributes: {
              "exception.type": error.type,
              "exception.message": error.message,
              "exception.stacktrace": `${error.type}: ${error.message}`,
            },
          },
        ]
      : [],
    duration: hrTime(endMs - startMs),
    ended: true,
    resource: { attributes: {} },
    instrumentationScope: { name: scope, version: "1.0.0" },
    droppedAttributesCount: 0,
    droppedEventsCount: 0,
    droppedLinksCount: 0,
  };
}

const SESSION_ID = "wrun_01TESTSESSION";

// A weather turn: the `eve` turn span wraps a model call (`chat`) that calls a
// tool (`execute_tool`). Time windows establish containment: turn ⊃ chat ⊃ tool.
const TURN_SPAN = makeSpan(
  "eve",
  "ai.eve.turn",
  {
    "eve.version": "0.11.5",
    "eve.environment": "development",
    "eve.session.id": SESSION_ID,
    "eve.turn.id": "turn_0",
  },
  { traceId: "trace1", spanId: "turn", startMs: 0, endMs: 1200 }
);

const CHAT_SPAN = makeSpan(
  "gen_ai",
  "chat claude-haiku-4-5",
  {
    "gen_ai.operation.name": "chat",
    "gen_ai.provider.name": "anthropic",
    "gen_ai.request.model": "claude-haiku-4-5",
    "gen_ai.input.messages": JSON.stringify([
      {
        role: "user",
        parts: [{ type: "text", content: "What is the weather in Brooklyn?" }],
      },
    ]),
    "gen_ai.output.messages": JSON.stringify([
      {
        role: "assistant",
        parts: [{ type: "text", content: "It's sunny in Brooklyn, 72°F." }],
        finish_reason: "stop",
      },
    ]),
    "gen_ai.response.finish_reasons": JSON.stringify(["stop"]),
    "gen_ai.usage.input_tokens": 5170,
    "gen_ai.usage.output_tokens": 54,
    "gen_ai.usage.cache_read.input_tokens": 5167,
    "gen_ai.usage.cache_creation.input_tokens": 48,
  },
  { traceId: "trace1", spanId: "chat", startMs: 100, endMs: 1100 }
);

const TOOL_SPAN = makeSpan(
  "gen_ai",
  "execute_tool get_weather",
  {
    "gen_ai.operation.name": "execute_tool",
    "gen_ai.tool.name": "get_weather",
    "gen_ai.tool.call.id": "toolu_01",
    "gen_ai.tool.call.arguments": JSON.stringify({ city: "Brooklyn" }),
    "gen_ai.tool.call.result": JSON.stringify({
      city: "Brooklyn",
      condition: "Sunny",
      temperatureF: 72,
    }),
  },
  { traceId: "trace1", spanId: "tool", startMs: 1000, endMs: 1050 }
);

// ─────────────────────────────── Harness ────────────────────────────────

describe("OpikExporter - AI SDK v7 / GenAI (eve) spans", () => {
  let client: Opik;
  let exporter: OpikExporter;
  let createTracesSpy: MockInstance<typeof client.api.traces.createTraces>;
  let createSpansSpy: MockInstance<typeof client.api.spans.createSpans>;
  let updateTraceSpy: MockInstance<typeof client.api.traces.updateTrace>;
  let updateSpanSpy: MockInstance<typeof client.api.spans.updateSpan>;

  beforeEach(() => {
    client = new Opik({ projectName: "opik-sdk-typescript" });
    exporter = new OpikExporter({ client });

    createTracesSpy = vi
      .spyOn(client.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction as never);
    createSpansSpy = vi
      .spyOn(client.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction as never);
    updateTraceSpy = vi
      .spyOn(client.api.traces, "updateTrace")
      .mockImplementation(mockAPIFunction as never);
    updateSpanSpy = vi
      .spyOn(client.api.spans, "updateSpan")
      .mockImplementation(mockAPIFunction as never);
  });

  // Push spans through the exporter's SpanExporter.export contract.
  const exportSpans = (...spans: unknown[]) =>
    new Promise<{ code: number }>((resolve) =>
      exporter.export(spans as never, resolve)
    );
  // The most recent trace payload sent to Opik.
  const lastTrace = () => createTracesSpy.mock.calls.at(-1)![0].traces.at(-1)!;
  // Every span payload sent to Opik, flattened across batches.
  const createdSpans = () =>
    createSpansSpy.mock.calls.flatMap((call) => call[0].spans);

  // ──────────────────────────────── Tests ─────────────────────────────────

  it("captures the prompt, answer and token usage in OpenAI chat shape", async () => {
    const result = await exportSpans(TURN_SPAN, CHAT_SPAN, TOOL_SPAN);

    expect(result.code).toBe(0);
    expect(lastTrace()).toMatchObject({
      // expected input — the user prompt, as OpenAI `messages`
      input: {
        messages: [{ role: "user", content: "What is the weather in Brooklyn?" }],
      },
      // output — the assistant answer, as OpenAI `choices`
      output: {
        choices: [
          {
            message: { role: "assistant", content: "It's sunny in Brooklyn, 72°F." },
            finish_reason: "stop",
          },
        ],
      },
      // token usage, including cache tokens
      usage: {
        prompt_tokens: 5170,
        completion_tokens: 54,
        total_tokens: 5224,
        "original_usage.cache_read_input_tokens": 5167,
        "original_usage.cache_creation_input_tokens": 48,
      },
      // multi-turn grouping via the eve session id
      threadId: SESSION_ID,
    });
  });

  it("logs the tool execution as a tool-typed span with args and result", async () => {
    await exportSpans(TURN_SPAN, CHAT_SPAN, TOOL_SPAN);

    const toolSpan = createdSpans().find(
      (span) => span.name === "execute_tool get_weather"
    );

    expect(toolSpan).toMatchObject({
      type: "tool",
      input: { toolName: "get_weather", args: { city: "Brooklyn" } },
      output: { result: { city: "Brooklyn", condition: "Sunny", temperatureF: 72 } },
    });
  });

  it("normalizes a tool-call conversation (text, tool_call, tool result)", async () => {
    const conversation = makeSpan(
      "gen_ai",
      "chat claude-haiku-4-5",
      {
        "gen_ai.operation.name": "chat",
        "gen_ai.input.messages": JSON.stringify([
          { role: "user", parts: [{ type: "text", content: "Weather in Brooklyn?" }] },
          {
            role: "assistant",
            parts: [
              {
                type: "tool_call",
                id: "call_1",
                name: "get_weather",
                arguments: { city: "Brooklyn" },
              },
            ],
          },
          {
            role: "tool",
            parts: [
              { type: "tool_call_response", id: "call_1", response: { condition: "Sunny" } },
            ],
          },
        ]),
        "gen_ai.usage.input_tokens": 10,
        "gen_ai.usage.output_tokens": 5,
      },
      { traceId: "conv", spanId: "chat", startMs: 0, endMs: 100 }
    );

    await exportSpans(conversation);

    expect(lastTrace()).toMatchObject({
      input: {
        messages: [
          { role: "user", content: "Weather in Brooklyn?" },
          {
            role: "assistant",
            tool_calls: [
              {
                id: "call_1",
                type: "function",
                function: { name: "get_weather", arguments: '{"city":"Brooklyn"}' },
              },
            ],
          },
          { role: "tool", tool_call_id: "call_1", content: '{"condition":"Sunny"}' },
        ],
      },
    });
  });

  it("types each span and nests them by time containment", async () => {
    await exportSpans(TURN_SPAN, CHAT_SPAN, TOOL_SPAN);

    const spans = createdSpans();
    const chat = spans.find((span) => span.name?.startsWith("chat"));
    const tool = spans.find((span) => span.name === "execute_tool get_weather");

    // The eve turn span becomes the trace itself, not a child span.
    expect(spans.some((span) => span.name === "ai.eve.turn")).toBe(false);
    expect(chat!.type).toBe("llm");
    expect(tool!.type).toBe("tool");
    // The tool ran inside the model call (turn ⊃ chat ⊃ tool by time).
    expect(tool!.parentSpanId).toBe(chat!.id);
  });

  it("captures error information from a failed model span", async () => {
    const erroredChat = makeSpan(
      "gen_ai",
      "chat claude-haiku-4-5",
      {
        "gen_ai.operation.name": "chat",
        "gen_ai.input.messages": JSON.stringify([
          { role: "user", parts: [{ type: "text", content: "hi" }] },
        ]),
      },
      {
        traceId: "err",
        spanId: "chat",
        startMs: 0,
        endMs: 100,
        error: { type: "APICallError", message: "Rate limit exceeded" },
      }
    );

    await exportSpans(erroredChat);

    expect(lastTrace()).toMatchObject({
      input: { messages: [{ role: "user", content: "hi" }] },
      errorInfo: { exceptionType: "APICallError", message: "Rate limit exceeded" },
    });
  });

  // ── create-only contract (upsert, never update) ──

  it("coalesces spans arriving across export batches into one trace", async () => {
    // The BatchSpanProcessor flushes a single turn's spans across batches.
    await exportSpans(TURN_SPAN, CHAT_SPAN);
    await exportSpans(TOOL_SPAN);

    // Re-sending may emit the trace once per batch, but always under one id.
    const traceIds = new Set(
      createTracesSpy.mock.calls.flatMap((call) =>
        call[0].traces.map((trace) => trace.id)
      )
    );
    expect(traceIds.size).toBe(1);
    expect(
      createdSpans().some((span) => span.name === "execute_tool get_weather")
    ).toBe(true);
  });

  it("backfills a late thread id via create, never update", async () => {
    // First batch: only the model call (no eve.session.id yet).
    await exportSpans(CHAT_SPAN);
    // Later batch: the eve turn span carries the session id.
    await exportSpans(TURN_SPAN, TOOL_SPAN);

    expect(updateTraceSpy).not.toHaveBeenCalled();
    expect(updateSpanSpy).not.toHaveBeenCalled();
    expect(lastTrace()).toMatchObject({ threadId: SESSION_ID });
  });
});
