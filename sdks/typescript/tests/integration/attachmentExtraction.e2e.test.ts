import { describe, it, expect, afterEach } from "vitest";
import { Opik } from "@/index";
import { shouldRunIntegrationTests } from "./api/shouldRunIntegrationTests";

/**
 * End-to-end verification of base64 attachment extraction (OPIK-7349) against a real Opik
 * backend (the local dev runner: OPIK_URL_OVERRIDE=http://localhost:8080).
 *
 * Each scenario logs an entity carrying a 2 MB base64 image with a 1 MB per-span cap, so an
 * inline image would be truncated if extraction did NOT run first. We assert:
 *   - the image was uploaded as an attachment (attachmentList returns it), and
 *   - the span/trace was NOT truncated (the image bypassed the size cap).
 * Note: the backend re-inlines attachments into input/output on read, so the stored field
 * holds the image bytes again, not the placeholder — hence we verify via attachmentList.
 */

const run = shouldRunIntegrationTests();
const MB = 1024 * 1024;
const TIMEOUT = 90_000;
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

const pngDataUri = (mb: number) => {
  const buf = Buffer.alloc(Math.round(mb * MB));
  PNG_SIGNATURE.forEach((byte, i) => (buf[i] = byte));
  return `data:image/png;base64,${buf.toString("base64")}`;
};

const apiUrl = process.env.OPIK_URL_OVERRIDE ?? "http://localhost:8080";
const encodedPath = Buffer.from(apiUrl, "utf8").toString("base64");

let projectSeq = 0;
const makeClient = () =>
  new Opik({
    projectName: `ts-attach-e2e-${Date.now()}-${projectSeq++}`,
    minBase64EmbeddedAttachmentSize: 1000, // small threshold: a modest image triggers extraction
    maxSpanPayloadSizeMb: 1, // small cap: a >1 MB inline image would truncate unless extracted first
  });

describe.skipIf(!run)("Attachment extraction E2E (OPIK-7349)", () => {
  let client: Opik;

  afterEach(async () => {
    if (client) await client.flush();
  });

  const poll = async <T>(
    fetchOne: () => Promise<T>,
    until: (value: T) => boolean
  ): Promise<T> => {
    const start = Date.now();
    let lastError: unknown;
    let lastValue: T | undefined;
    while (Date.now() - start < 30_000) {
      try {
        lastValue = await fetchOne();
        if (lastValue && until(lastValue)) return lastValue;
      } catch (error) {
        lastError = error;
      }
      await new Promise((r) => setTimeout(r, 500));
    }
    throw new Error(
      `condition not met within timeout; last value: ${JSON.stringify(lastValue)}; last error: ${lastError}`
    );
  };

  const listAttachments = (
    entityType: "span" | "trace",
    entityId: string,
    projectId: string
  ) =>
    client.api.attachments.attachmentList({
      projectId,
      entityType,
      entityId,
      path: encodedPath,
    });

  it(
    "span: a 2 MB base64 image is uploaded as an attachment, bypassing the 1 MB cap",
    async () => {
      client = makeClient();
      const trace = client.trace({ name: "attach-e2e-trace" });
      const span = trace.span({
        name: "attach-e2e-span",
        type: "general",
        input: { image: pngDataUri(2) },
      });
      span.end();
      trace.end();
      await client.flush();

      const stored = (await poll(
        () =>
          client.api.spans.getSpanById(span.data.id) as unknown as Promise<
            Record<string, unknown>
          >,
        (s) => s.input != null
      )) as { input: Record<string, unknown>; projectId: string };

      // Extracted, so it never hit the per-span cap — no truncation marker.
      expect(stored.input.opik_truncated).toBeUndefined();

      // The image landed as an attachment on the span.
      const list = await poll(
        () => listAttachments("span", span.data.id, stored.projectId),
        (l) => (l.content ?? []).length >= 1
      );
      expect(list.content?.[0].mimeType).toContain("image/png");
    },
    TIMEOUT
  );

  it(
    "trace: a base64 image on a trace is uploaded as an attachment too (parity)",
    async () => {
      client = makeClient();
      const trace = client.trace({
        name: "attach-e2e-trace-only",
        input: { image: pngDataUri(2) },
      });
      trace.end();
      await client.flush();

      const stored = (await poll(
        () =>
          client.api.traces.getTraceById(trace.data.id) as unknown as Promise<
            Record<string, unknown>
          >,
        (t) => t.input != null
      )) as { input: Record<string, unknown>; projectId: string };

      const list = await poll(
        () => listAttachments("trace", trace.data.id, stored.projectId),
        (l) => (l.content ?? []).length >= 1
      );
      expect(list.content?.[0].mimeType).toContain("image/png");
    },
    TIMEOUT
  );
});
