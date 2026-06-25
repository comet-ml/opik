import { createAnthropic, type AnthropicProvider } from "@ai-sdk/anthropic";
import { trace } from "@opentelemetry/api";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { generateText, streamText } from "ai";
import { Opik } from "opik";
import { OpikExporter } from "../src/exporter";
import { mockAPIFunction } from "./mockUtils";

/**
 * Real-LLM integration test.
 *
 * A live Anthropic call flows through the OpenTelemetry pipeline into
 * OpikExporter; we assert the trace payloads the exporter produces. The Opik
 * client is spied, so no Opik backend is required.
 *
 * Design for non-flakiness:
 * - Skipped unless ANTHROPIC_API_KEY is set (CI without a key stays green).
 * - Assertions are presence-based — the prompt is present, SOME output exists,
 *   token usage is present, errors are captured. We never assert on the model's
 *   wording or whether it chose to call a tool (that behavior is covered
 *   deterministically in gen-ai.test.ts).
 */

const MODEL = "claude-haiku-4-5";
const PROMPT = "Reply with a one-sentence greeting.";

// Minimal views of the payloads the exporter sends to the Opik REST client.
type TracePayload = {
  input?: unknown;
  output?: Record<string, unknown>;
  usage?: Record<string, number>;
  errorInfo?: { exceptionType?: string };
};

type SpanPayload = {
  type?: string;
  usage?: Record<string, number>;
};

type Captured = {
  trace: TracePayload;
  spans: SpanPayload[];
  updateCalled: boolean;
};

describe.skipIf(!process.env.ANTHROPIC_API_KEY)(
  "OpikExporter - real Anthropic call",
  () => {
    // ─────────────────────────── Setup ───────────────────────────
    let client: Opik;
    let sdk: NodeSDK;
    let anthropic: AnthropicProvider;

    beforeAll(() => {
      // Claude Code injects ANTHROPIC_BASE_URL (a local proxy); clear it so the
      // AI SDK reaches the real Anthropic API.
      delete process.env.ANTHROPIC_BASE_URL;
    });

    beforeEach(() => {
      trace.disable();
      client = new Opik({ projectName: "opik-sdk-typescript" });
      sdk = new NodeSDK({
        traceExporter: new OpikExporter({ client }),
        instrumentations: [getNodeAutoInstrumentations()],
      });
      anthropic = createAnthropic();
    });

    // Runs `call`, flushes telemetry, and returns the captured trace payload.
    async function capture(call: () => Promise<void>): Promise<Captured> {
      const createTraces = vi
        .spyOn(client.api.traces, "createTraces")
        .mockImplementation(mockAPIFunction as never);
      const updateTrace = vi
        .spyOn(client.api.traces, "updateTrace")
        .mockImplementation(mockAPIFunction as never);
      const updateSpan = vi
        .spyOn(client.api.spans, "updateSpan")
        .mockImplementation(mockAPIFunction as never);
      const createSpans = vi
        .spyOn(client.api.spans, "createSpans")
        .mockImplementation(mockAPIFunction as never);

      sdk.start();
      await call();
      await sdk.shutdown();

      const traces = createTraces.mock.calls.flatMap(
        (c) => c[0].traces
      ) as TracePayload[];
      const spans = createSpans.mock.calls.flatMap(
        (c) => c[0].spans
      ) as SpanPayload[];

      return {
        trace: traces.at(-1)!,
        spans,
        updateCalled:
          updateTrace.mock.calls.length > 0 || updateSpan.mock.calls.length > 0,
      };
    }

    // Token usage must live on an LLM span, never on the trace.
    function expectUsageOnLlmSpanOnly(captured: Captured) {
      expect((captured.trace as { usage?: unknown }).usage).toBeUndefined();
      const llmWithUsage = captured.spans.filter(
        (span) => span.type === "llm" && (span.usage?.total_tokens ?? 0) > 0
      );
      expect(llmWithUsage.length).toBeGreaterThan(0);
      // No non-LLM span carries usage.
      const nonLlmWithUsage = captured.spans.filter(
        (span) => span.type !== "llm" && span.usage !== undefined
      );
      expect(nonLlmWithUsage).toHaveLength(0);
    }

    // ─────────────────────────── Tests ───────────────────────────

    it("generateText logs the prompt, some output and token usage", async () => {
      const captured = await capture(async () => {
        await generateText({
          model: anthropic(MODEL),
          prompt: PROMPT,
          experimental_telemetry: OpikExporter.getSettings({ name: "real-generate" }),
        });
      });

      expect(JSON.stringify(captured.trace.input)).toContain(PROMPT); // expected input
      expect(Object.keys(captured.trace.output ?? {}).length).toBeGreaterThan(0); // some output
      expect(captured.updateCalled).toBe(false); // create-only
      expectUsageOnLlmSpanOnly(captured); // token usage, LLM span only
    }, 60_000);

    it("streamText logs the prompt, some output and token usage", async () => {
      const captured = await capture(async () => {
        const result = streamText({
          model: anthropic(MODEL),
          prompt: PROMPT,
          experimental_telemetry: OpikExporter.getSettings({ name: "real-stream" }),
        });
        // streamText telemetry only finalizes once the stream is drained.
        await result.consumeStream();
      });

      expect(JSON.stringify(captured.trace.input)).toContain(PROMPT); // expected input
      expect(Object.keys(captured.trace.output ?? {}).length).toBeGreaterThan(0); // some output
      expect(captured.updateCalled).toBe(false); // create-only
      expectUsageOnLlmSpanOnly(captured); // token usage, LLM span only
    }, 60_000);

    it("captures error information when the model call fails", async () => {
      let threw = false;

      const { trace } = await capture(async () => {
        try {
          await generateText({
            model: anthropic("claude-nonexistent-model-xyz"),
            prompt: PROMPT,
            experimental_telemetry: OpikExporter.getSettings({ name: "real-error" }),
          });
        } catch {
          threw = true; // the failing model call must surface to the caller
        }
      });

      expect(threw).toBe(true);
      expect(trace.errorInfo?.exceptionType).toBeTruthy(); // error information
    }, 60_000);
  }
);
