import { SavedTrace } from "@/tracer/Trace";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import {
  extractAndUploadAttachments,
  type AttachmentUploadConfig,
} from "./attachment";

type AttachmentPayload = {
  input?: unknown;
  output?: unknown;
  metadata?: unknown;
  projectName?: string;
};

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
    private readonly attachmentUpload?: AttachmentUploadConfig,
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      name: "TraceBatchQueue",
    });
  }

  protected getId(entity: SavedTrace) {
    return entity.id;
  }

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
      { entityType: "trace", entityId, projectName: payload.projectName },
      payload,
    );
  }

  protected async createEntities(traces: SavedTrace[]) {
    const payload: SavedTrace[] = [];
    for (const trace of traces) {
      payload.push(await this.extractAttachments(trace, trace.id));
    }
    await this.api.traces.createTraces(
      { traces: payload },
      this.api.requestOptions,
    );
  }

  protected async getEntity(id: string) {
    return (await this.api.traces.getTraceById(
      id,
      {},
      this.api.requestOptions,
    )) as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    const body = await this.extractAttachments(updates, id);
    await this.api.traces.updateTrace(id, { body }, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    await this.api.traces.deleteTraces({ ids }, this.api.requestOptions);
  }
}
