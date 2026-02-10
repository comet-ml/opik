import { OpikClient } from "@/client/Client";
import { TracePublic, AnnotationQueuePublic } from "@/rest_api/api";
import { splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import { AnnotationQueueItemMissingIdError } from "./errors";
import {
  BaseAnnotationQueue,
  AnnotationQueueData,
  ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE,
} from "./BaseAnnotationQueue";

export class TracesAnnotationQueue extends BaseAnnotationQueue {
  static readonly SCOPE = "trace" as const;

  constructor(
    data: AnnotationQueueData | AnnotationQueuePublic,
    opik: OpikClient
  ) {
    super(data, opik);
  }

  private extractTraceIds(traces: TracePublic[]): string[] {
    return traces.map((trace, index) => {
      if (!trace.id) {
        throw new AnnotationQueueItemMissingIdError("trace", index);
      }
      return trace.id;
    });
  }

  public async addTraces(traces: TracePublic[]): Promise<void> {
    if (!traces || traces.length === 0) {
      return;
    }

    const ids = this.extractTraceIds(traces);
    const batches = splitIntoBatches(ids, {
      maxBatchSize: ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE,
    });

    for (const batch of batches) {
      logger.debug(`Adding ${batch.length} traces to annotation queue`);
      await this.addItemsBatch(batch);
    }
  }

  public async removeTraces(traces: TracePublic[]): Promise<void> {
    if (!traces || traces.length === 0) {
      return;
    }

    const ids = this.extractTraceIds(traces);
    const batches = splitIntoBatches(ids, {
      maxBatchSize: ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE,
    });

    for (const batch of batches) {
      logger.debug(`Removing ${batch.length} traces from annotation queue`);
      await this.removeItemsBatch(batch);
    }
  }
}
