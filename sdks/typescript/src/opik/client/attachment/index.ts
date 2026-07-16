import { logger } from "@/utils/logger";
import type { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { extractInlineAttachments } from "./attachmentExtraction";
import {
  uploadInlineAttachment,
  type AttachmentUploadConfig,
  type AttachmentUploadTarget,
} from "./attachmentUpload";

export type { AttachmentUploadConfig, AttachmentUploadTarget };

type AttachmentSource = {
  input?: unknown;
  output?: unknown;
  metadata?: unknown;
};

/**
 * Extract inline base64 blobs from a span/trace payload, upload them as attachments, and
 * return the sanitized payload (placeholders in place of the blobs). Run this BEFORE any
 * size measurement so extracted images don't count toward the per-span cap.
 *
 * Best-effort and non-fatal: if extraction or an upload fails, the write still proceeds.
 * On an upload failure the placeholder is kept (the field stays small) and a warning logged.
 */
export const extractAndUploadAttachments = async <T extends AttachmentSource>(
  api: OpikApiClientTemp,
  config: AttachmentUploadConfig,
  target: AttachmentUploadTarget,
  payload: T,
): Promise<T> => {
  let extraction: ReturnType<typeof extractInlineAttachments<T>>;
  try {
    extraction = extractInlineAttachments(payload, config.minSizeBytes);
  } catch (error) {
    logger.warn(
      `Attachment extraction skipped for ${target.entityType} '${target.entityId}': ${error}`,
    );
    return payload;
  }

  if (extraction.attachments.length === 0) {
    return payload;
  }

  await Promise.all(
    extraction.attachments.map((attachment) =>
      uploadInlineAttachment(api, config, target, attachment).catch((error) => {
        logger.warn(
          `Failed to upload extracted attachment '${attachment.fileName}' ` +
            `for ${target.entityType} '${target.entityId}': ${error}`,
        );
      }),
    ),
  );

  return extraction.result;
};
