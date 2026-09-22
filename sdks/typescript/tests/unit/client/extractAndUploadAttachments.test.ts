import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { extractAndUploadAttachments } from "@/client/attachment";
import { logger } from "@/utils/logger";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const pngBase64 = (bytes: number) => {
  const buf = Buffer.alloc(bytes);
  PNG.forEach((b, i) => (buf[i] = b));
  return buf.toString("base64");
};

const config = {
  minSizeBytes: 1000,
  apiUrl: "http://localhost:8080",
  workspaceName: "default",
};
const target = { entityType: "span" as const, entityId: "s1" };
const PLACEHOLDER = /^\[input-attachment-\d+-\d+-sdk\.png\]$/;

describe("extractAndUploadAttachments (fail-safe)", () => {
  let startMultiPartUpload: MockInstance;
  let warn: MockInstance;
  let api: {
    attachments: {
      startMultiPartUpload: MockInstance;
      completeMultiPartUpload: MockInstance;
    };
    requestOptions: unknown;
  };

  beforeEach(() => {
    startMultiPartUpload = vi.fn();
    api = {
      attachments: {
        startMultiPartUpload,
        completeMultiPartUpload: vi.fn().mockResolvedValue(undefined),
      },
      requestOptions: {},
    };
    warn = vi.spyOn(logger, "warn").mockImplementation(() => undefined);
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 204 }),
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const payloadWithImage = () => ({
    input: { image: `data:image/png;base64,${pngBase64(1000)}` },
  });

  it("swallows an upload failure and still returns the sanitized payload (placeholder kept)", async () => {
    // The upload rejects with a message carrying a presigned-URL-like string.
    startMultiPartUpload.mockRejectedValue(
      new Error("network error to https://s3.example/part?sig=SECRETSIG"),
    );

    const result = await extractAndUploadAttachments(
      api as unknown as OpikApiClientTemp,
      config,
      target,
      payloadWithImage(),
    );

    // No throw; the write proceeds with the small placeholder rather than the raw blob.
    expect((result.input as { image: string }).image).toMatch(PLACEHOLDER);
    expect(startMultiPartUpload).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it("returns the original payload untouched when there is nothing to extract", async () => {
    const payload = { input: { text: "small" }, output: { ok: true } };

    const result = await extractAndUploadAttachments(
      api as unknown as OpikApiClientTemp,
      config,
      target,
      payload,
    );

    expect(result).toBe(payload); // same reference — no extraction, no upload attempt
    expect(startMultiPartUpload).not.toHaveBeenCalled();
  });
});
