import { SavedSpan } from "@/tracer/Span";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { truncateSpanIfNeeded } from "./spanTruncation";
import { DEFAULT_CONFIG } from "@/config/Config";
import {
  extractAndUploadAttachments,
  type AttachmentUploadConfig,
} from "./attachment";

type SpanUpdate = Partial<SavedSpan> & { traceId: string };
type AttachmentPayload = {
  input?: unknown;
  output?: unknown;
  metadata?: unknown;
  projectName?: string;
};

export class SpanBatchQueue extends BatchQueue<SavedSpan> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
    private readonly maxSpanPayloadSizeMb?: number,
    private readonly attachmentUpload?: AttachmentUploadConfig,
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      name: "SpanBatchQueue",
    });
  }

  protected getId(entity: SavedSpan) {
    return entity.id;
  }

  // Extract inline base64 attachments (when enabled) BEFORE truncation, so images become
  // attachments and no longer count toward the per-span size cap.
  private async extractAttachments<T extends AttachmentPayload>(
    payload: T,
    entityId: string,
  ): Promise<T> {
    if (!this.attachmentUpload) {
      return payload;
    }
    return extractAndUploadAttachments(
      this.api,
      this.attachmentUpload,
      { entityType: "span", entityId, projectName: payload.projectName },
      payload,
    );
  }

  protected async createEntities(spans: SavedSpan[]) {
    const payload: SavedSpan[] = [];
    for (const span of spans) {
      const extracted = await this.extractAttachments(span, span.id);
      payload.push(
        truncateSpanIfNeeded(
          extracted,
          this.maxSpanPayloadSizeMb ?? DEFAULT_CONFIG.maxSpanPayloadSizeMb,
          span.id,
        ),
      );
    }
    await this.api.spans.createSpans(
      { spans: payload },
      this.api.requestOptions,
    );
  }

  protected async getEntity(id: string) {
    return (await this.api.spans.getSpanById(
      id,
      {},
      this.api.requestOptions,
    )) as SavedSpan;
  }

  protected async updateEntity(id: string, updates: SpanUpdate) {
    const extracted = await this.extractAttachments(updates, id);
    const body = truncateSpanIfNeeded(
      extracted,
      this.maxSpanPayloadSizeMb ?? DEFAULT_CONFIG.maxSpanPayloadSizeMb,
      id,
    );
    await this.api.spans.updateSpan(id, { body }, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    for (const id of ids) {
      await this.api.spans.deleteSpanById(id, this.api.requestOptions);
    }
  }
}
