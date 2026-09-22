import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import type { ExtractedAttachment } from "./attachmentExtraction";

/**
 * Uploads an extracted base64 blob as an Opik attachment (parity with the Python SDK's
 * file_upload). The backend decides the path: a `BEMinIO` upload id means "PUT the bytes
 * to the single local URL"; anything else is an S3 multipart upload (PUT each part to its
 * presigned URL, collect the ETags, then complete).
 */

const LOCAL_UPLOAD_MAGIC_ID = "BEMinIO";
const PART_SIZE_BYTES = 5 * 1024 * 1024; // S3 minimum part size

// Static config resolved once from OpikConfig; carries what the raw PUT to the local
// backend needs (that PUT bypasses the generated client, so it must supply auth itself).
export interface AttachmentUploadConfig {
  minSizeBytes: number;
  apiUrl: string;
  workspaceName: string;
  apiKey?: string;
  extraHeaders?: Record<string, string>;
}

export interface AttachmentUploadTarget {
  entityType: "span" | "trace";
  entityId: string;
  projectName?: string;
}

const putBytes = async (
  url: string,
  bytes: Buffer,
  headers?: Record<string, string>,
): Promise<string | null> => {
  const response = await fetch(url, {
    method: "PUT",
    body: bytes as unknown as BodyInit,
    headers,
  });
  if (!response.ok) {
    throw new Error(
      `attachment PUT failed: ${response.status} ${response.statusText}`,
    );
  }
  return response.headers.get("etag");
};

export const uploadInlineAttachment = async (
  api: OpikApiClientTemp,
  config: AttachmentUploadConfig,
  target: AttachmentUploadTarget,
  attachment: ExtractedAttachment,
): Promise<void> => {
  const { data, fileName, mimeType } = attachment;
  const numOfFileParts = Math.max(1, Math.ceil(data.length / PART_SIZE_BYTES));
  const path = Buffer.from(config.apiUrl, "utf8").toString("base64");

  const response = await api.attachments.startMultiPartUpload(
    {
      fileName,
      numOfFileParts,
      mimeType,
      entityType: target.entityType,
      entityId: target.entityId,
      path,
      projectName: target.projectName,
    },
    api.requestOptions,
  );

  if (response.uploadId === LOCAL_UPLOAD_MAGIC_ID) {
    // Local backend: a single authenticated PUT of the whole file, no completion call.
    const headers: Record<string, string> = {
      "Content-Type": mimeType,
      "Comet-Workspace": config.workspaceName,
      ...config.extraHeaders,
    };
    if (config.apiKey) {
      headers.authorization = config.apiKey;
    }
    await putBytes(response.preSignUrls[0], data, headers);
    return;
  }

  // Cloud: PUT each part to its presigned S3 URL (self-authenticating), collect the
  // ETags, then finalize the multipart upload on the backend.
  const uploadedFileParts = [];
  for (let i = 0; i < response.preSignUrls.length; i++) {
    const start = i * PART_SIZE_BYTES;
    const chunk = data.subarray(start, start + PART_SIZE_BYTES);
    const eTag = await putBytes(response.preSignUrls[i], chunk);
    if (!eTag) {
      throw new Error(
        `attachment upload part ${i + 1} returned no ETag; cannot complete multipart upload`,
      );
    }
    uploadedFileParts.push({ eTag, partNumber: i + 1 });
  }
  await api.attachments.completeMultiPartUpload(
    {
      fileName,
      entityType: target.entityType,
      entityId: target.entityId,
      fileSize: data.length,
      mimeType,
      uploadId: response.uploadId,
      uploadedFileParts,
      projectName: target.projectName,
    },
    api.requestOptions,
  );
};
