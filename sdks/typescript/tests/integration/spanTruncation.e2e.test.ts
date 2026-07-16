import { describe, it, expect, afterEach } from "vitest";
import { Opik } from "@/index";
import { shouldRunIntegrationTests } from "./api/shouldRunIntegrationTests";

/**
 * End-to-end verification of per-span truncation (OPIK-7349) against a real
 * Opik backend (the local "dev runner": OPIK_URL_OVERRIDE=http://localhost:8080).
 *
 * Each scenario logs a span through the normal SDK path (trace.span / span.update
 * -> SpanBatchQueue -> createSpans/updateSpan), flushes, then fetches the stored
 * span back from the backend and asserts what actually landed:
 *   - oversized field -> replaced by the truncation marker on the server copy;
 *   - small sibling   -> preserved;
 *   - hard per-span cap (total over, no single field over) -> everything truncated;
 *   - limit disabled  -> full payload stored (no marker);
 *   - configurable limit -> a field under the default 20 MB is still truncated
 *     when the client is configured with a lower limit;
 *   - update path     -> truncation applies on span.update too.
 *
 * Large payloads are built from many ~100 KB chunks rather than one giant string,
 * so the *document* is large (what our truncation measures) while every single
 * string stays well under any backend per-string limit — keeping the test valid
 * against a stock backend.
 */

const run = shouldRunIntegrationTests();
const MB = 1024 * 1024;
const CHUNK = "x".repeat(100 * 1024); // 100 KB — far below any per-string cap

// A payload whose JSON size is ~`mb` MB, composed of many small chunks.
const big = (mb: number) => {
  const n = Math.max(1, Math.round((mb * MB) / CHUNK.length));
  return { items: Array.from({ length: n }, () => CHUNK) };
};

const asMarker = (v: unknown) =>
  v as { opik_truncated?: boolean; reason?: string; items?: unknown };

const MARKER_RE = /^<omitted_due_to_size_\d+MB_error_code_413_400>$/;
const TIMEOUT = 90_000;

let projectSeq = 0;
const makeClient = (maxSpanPayloadSizeMb?: number) =>
  new Opik({
    projectName: `ts-trunc-e2e-${Date.now()}-${projectSeq++}`,
    ...(maxSpanPayloadSizeMb !== undefined ? { maxSpanPayloadSizeMb } : {}),
  });

describe.skipIf(!run)("Span truncation E2E (OPIK-7349)", () => {
  let client: Opik;

  afterEach(async () => {
    if (client) await client.flush();
  });

  // Poll the backend until the span is indexed and `until` holds. The predicate
  // guards against reading a partially-written span: a create followed by an
  // update lands as two writes, so "row exists" is not the same as "update
  // applied" — callers touching the update path wait for the field to appear.
  const fetchSpan = async (
    id: string,
    until: (span: Record<string, unknown>) => boolean = () => true
  ) => {
    const start = Date.now();
    while (Date.now() - start < 30_000) {
      try {
        const span = (await client.api.spans.getSpanById(
          id
        )) as unknown as Record<string, unknown>;
        if (span && until(span)) return span;
      } catch {
        // not indexed yet
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error(`span ${id} not found (or predicate unmet) within timeout`);
  };

  const logSpan = async (fields: {
    input?: Record<string, unknown>;
    output?: Record<string, unknown>;
    metadata?: Record<string, unknown>;
  }) => {
    const trace = client.trace({ name: "trunc-e2e-trace", input: { t: 1 } });
    const span = trace.span({ name: "trunc-e2e-span", type: "general", ...fields });
    span.end();
    trace.end();
    await client.flush();
    return span.data.id;
  };

  it(
    "default 20 MB: truncates the oversized field, keeps the small sibling",
    async () => {
      client = makeClient(20);
      const id = await logSpan({ input: { prompt: "small" }, output: big(24) });

      const stored = fetchSpanOutput(await fetchSpan(id));
      expect(asMarker(stored.output).opik_truncated).toBe(true);
      expect(asMarker(stored.output).reason).toMatch(MARKER_RE);
      expect(stored.input).toEqual({ prompt: "small" }); // sibling preserved
    },
    TIMEOUT
  );

  it(
    "hard per-span cap: total over 20 MB but no single field over -> all truncated",
    async () => {
      client = makeClient(20);
      const id = await logSpan({ input: big(12), output: big(12) }); // 24 MB total

      const stored = fetchSpanOutput(await fetchSpan(id));
      expect(asMarker(stored.input).opik_truncated).toBe(true);
      expect(asMarker(stored.output).opik_truncated).toBe(true);
    },
    TIMEOUT
  );

  it(
    "limit disabled (0): full payload is stored unchanged",
    async () => {
      client = makeClient(0);
      const id = await logSpan({ output: big(24) });

      const stored = fetchSpanOutput(await fetchSpan(id));
      expect(asMarker(stored.output).opik_truncated).toBeUndefined();
      expect(Array.isArray(asMarker(stored.output).items)).toBe(true); // stored whole
    },
    TIMEOUT
  );

  it(
    "configurable limit (5 MB): a 6 MB field is truncated even though it's under the 20 MB default",
    async () => {
      client = makeClient(5);
      const id = await logSpan({ output: big(6) });

      const stored = fetchSpanOutput(await fetchSpan(id));
      expect(asMarker(stored.output).opik_truncated).toBe(true);
      expect(asMarker(stored.output).reason).toMatch(MARKER_RE);
    },
    TIMEOUT
  );

  it(
    "update path: truncation applies on span.update()",
    async () => {
      client = makeClient(20);
      const trace = client.trace({ name: "trunc-e2e-trace-upd", input: { t: 1 } });
      const span = trace.span({ name: "trunc-e2e-span-upd", type: "general" });
      span.update({ output: big(24) }); // oversized via the update path
      span.end();
      trace.end();
      await client.flush();

      // Wait for the update to be applied, not merely for the row to exist.
      const stored = fetchSpanOutput(
        await fetchSpan(span.data.id, (s) => s.output != null)
      );
      expect(asMarker(stored.output).opik_truncated).toBe(true);
      expect(asMarker(stored.output).reason).toMatch(MARKER_RE);
    },
    TIMEOUT
  );

  it(
    "large metadata is left intact and does not truncate input/output",
    async () => {
      client = makeClient(20);
      const id = await logSpan({
        input: { prompt: "small" },
        output: { result: "small" },
        metadata: big(24), // exempt: sent whole, must be accepted + stored intact
      });

      const stored = fetchSpanOutput(await fetchSpan(id));
      expect(asMarker(stored.metadata).opik_truncated).toBeUndefined();
      expect(Array.isArray(asMarker(stored.metadata).items)).toBe(true); // stored whole
      expect(asMarker(stored.input).opik_truncated).toBeUndefined();
      expect(asMarker(stored.output).opik_truncated).toBeUndefined();
    },
    TIMEOUT
  );

  it(
    "under limit: a small span is stored intact (no marker)",
    async () => {
      client = makeClient(20);
      const id = await logSpan({
        input: { prompt: "hello" },
        output: { result: "world" },
      });

      const stored = fetchSpanOutput(await fetchSpan(id));
      expect(asMarker(stored.output).opik_truncated).toBeUndefined();
      expect(stored.output).toEqual({ result: "world" });
      expect(stored.input).toEqual({ prompt: "hello" });
    },
    TIMEOUT
  );
});

// The backend returns input/output/metadata on the span record; narrow to those.
function fetchSpanOutput(span: Record<string, unknown>) {
  return {
    input: span.input,
    output: span.output,
    metadata: span.metadata,
  };
}
