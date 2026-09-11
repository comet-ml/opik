import { OpikClient } from "@/client/Client";
import { TraceThread, AnnotationQueuePublic } from "@/rest_api/api";
import { serialization } from "@/rest_api";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { parseThreadFilterString } from "@/utils/searchHelpers";
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

  /**
   * Fetches all threads currently assigned to this annotation queue.
   *
   * @param options.truncateImages When true (default), truncates inline base64
   *   image data in input, output and metadata to slim payloads.
   * @returns The threads in the queue.
   */
  public async getItems(options?: {
    truncateImages?: boolean;
  }): Promise<TraceThread[]> {
    const truncate = options?.truncateImages ?? true;
    const filters = parseThreadFilterString(
      `annotation_queue_ids contains "${this.id}"`
    );

    logger.debug(`Fetching items from annotation queue "${this.name}"`);

    const threads = await this.fetchAllItems<TraceThread>(
      async (limit, lastRetrievedThreadModelId) => {
        const streamResponse =
          await this.opik.api.traces.searchTraceThreads({
            projectId: this.projectId,
            filters: filters ?? undefined,
            limit,
            lastRetrievedThreadModelId,
            truncate,
          });

        return parseNdjsonStreamToArray<TraceThread>(
          streamResponse,
          serialization.TraceThread,
          limit
        );
      },
      (thread) => thread.threadModelId
    );

    logger.debug(
      `Fetched ${threads.length} items from annotation queue "${this.name}"`
    );

    return threads;
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
