import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import { uploadInlineAttachment } from "@/client/attachment/attachmentUpload";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";

const config = {
  minSizeBytes: 1000,
  apiUrl: "http://localhost:8080",
  workspaceName: "default",
  apiKey: "key-123",
};
const target = {
  entityType: "span" as const,
  entityId: "span-1",
  projectName: "proj",
};
const attachment = {
  data: Buffer.from("hello-attachment-bytes"),
  fileName: "input-attachment-1-1-sdk.png",
  mimeType: "image/png",
};
const expectedPath = Buffer.from(config.apiUrl, "utf8").toString("base64");

describe("uploadInlineAttachment", () => {
  let startMultiPartUpload: MockInstance;
  let completeMultiPartUpload: MockInstance;
  let fetchSpy: MockInstance;
  let api: {
    attachments: {
      startMultiPartUpload: MockInstance;
      completeMultiPartUpload: MockInstance;
    };
    requestOptions: unknown;
  };

  beforeEach(() => {
    startMultiPartUpload = vi.fn();
    completeMultiPartUpload = vi.fn().mockResolvedValue(undefined);
    api = {
      attachments: { startMultiPartUpload, completeMultiPartUpload },
      requestOptions: { headers: {} },
    };
    fetchSpy = vi.spyOn(globalThis, "fetch");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  const run = () =>
    uploadInlineAttachment(
      api as unknown as OpikApiClientTemp,
      config,
      target,
      attachment,
    );

  it("local (BEMinIO): starts, PUTs the bytes to the returned URL, no complete call", async () => {
    startMultiPartUpload.mockResolvedValue({
      uploadId: "BEMinIO",
      preSignUrls: ["http://localhost:8080/upload?x=1"],
    });
    fetchSpy.mockResolvedValue(new Response(null, { status: 204 }));

    await run();

    expect(startMultiPartUpload).toHaveBeenCalledTimes(1);
    expect(startMultiPartUpload.mock.calls[0][0]).toMatchObject({
      fileName: attachment.fileName,
      entityType: "span",
      entityId: "span-1",
      mimeType: "image/png",
      numOfFileParts: 1,
      projectName: "proj",
      path: expectedPath,
    });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/upload?x=1");
    expect(init.method).toBe("PUT");
    const headers = init.headers as Record<string, string>;
    expect(headers["Comet-Workspace"]).toBe("default");
    expect(headers.authorization).toBe("key-123");
    expect(completeMultiPartUpload).not.toHaveBeenCalled();
  });

  it("cloud (S3): PUTs each part and completes with the collected ETags", async () => {
    startMultiPartUpload.mockResolvedValue({
      uploadId: "s3-upload-id",
      preSignUrls: ["https://s3.example/part-1"],
    });
    fetchSpy.mockResolvedValue(
      new Response(null, { status: 200, headers: { etag: '"abc123"' } }),
    );

    await run();

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(completeMultiPartUpload).toHaveBeenCalledTimes(1);
    const completeArg = completeMultiPartUpload.mock.calls[0][0] as {
      uploadId: string;
      fileSize: number;
      uploadedFileParts: { eTag: string; partNumber: number }[];
    };
    expect(completeArg.uploadId).toBe("s3-upload-id");
    expect(completeArg.fileSize).toBe(attachment.data.length);
    expect(completeArg.uploadedFileParts[0]).toMatchObject({
      eTag: '"abc123"',
      partNumber: 1,
    });
  });

  it("throws when the upload PUT fails", async () => {
    startMultiPartUpload.mockResolvedValue({
      uploadId: "BEMinIO",
      preSignUrls: ["http://localhost:8080/upload"],
    });
    fetchSpy.mockResolvedValue(new Response("boom", { status: 500 }));

    await expect(run()).rejects.toThrow(/500/);
  });
});
