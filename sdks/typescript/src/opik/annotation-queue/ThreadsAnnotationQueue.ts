import { OpikClient } from "@/client/Client";
import { TraceThread, AnnotationQueuePublic } from "@/rest_api/api";
import { splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import { AnnotationQueueItemMissingIdError } from "./errors";
import {
  BaseAnnotationQueue,
  AnnotationQueueData,
  ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE,
} from "./BaseAnnotationQueue";

export class ThreadsAnnotationQueue extends BaseAnnotationQueue {
  static readonly SCOPE = "thread" as const;

  constructor(
    data: AnnotationQueueData | AnnotationQueuePublic,
    opik: OpikClient
  ) {
    super(data, opik);
  }

  private extractThreadIds(threads: TraceThread[]): string[] {
    return threads.map((thread, index) => {
      if (!thread.threadModelId) {
        throw new AnnotationQueueItemMissingIdError("thread", index);
      }
      return thread.threadModelId;
    });
  }

  public async addThreads(threads: TraceThread[]): Promise<void> {
    if (!threads || threads.length === 0) {
      return;
    }

    const ids = this.extractThreadIds(threads);
    const batches = splitIntoBatches(ids, {
      maxBatchSize: ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE,
    });

    for (const batch of batches) {
      logger.debug(`Adding ${batch.length} threads to annotation queue`);
      await this.addItemsBatch(batch);
    }
  }

  public async removeThreads(threads: TraceThread[]): Promise<void> {
    if (!threads || threads.length === 0) {
      return;
    }

    const ids = this.extractThreadIds(threads);
    const batches = splitIntoBatches(ids, {
      maxBatchSize: ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE,
    });

    for (const batch of batches) {
      logger.debug(`Removing ${batch.length} threads from annotation queue`);
      await this.removeItemsBatch(batch);
    }
  }
}
