import { OpikClient } from "@/client/Client";
import { TracePublic, AnnotationQueuePublic } from "@/rest_api/api";
import { serialization } from "@/rest_api";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { parseFilterString } from "@/utils/searchHelpers";
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

  /**
   * Fetches all traces currently assigned to this annotation queue.
   *
   * @param options.truncateImages When true (default), truncates inline base64
   *   image data in input, output and metadata to slim payloads.
   * @returns The traces in the queue.
   */
  public async getItems(options?: {
    truncateImages?: boolean;
  }): Promise<TracePublic[]> {
    const truncate = options?.truncateImages ?? true;
    const filters = parseFilterString(
      `annotation_queue_ids contains "${this.id}"`
    );

    logger.debug(`Fetching items from annotation queue "${this.name}"`);

    const traces = await this.fetchAllItems<TracePublic>(
      async (limit, lastRetrievedId) => {
        const streamResponse = await this.opik.api.traces.searchTraces({
          projectId: this.projectId,
          filters: filters ?? undefined,
          limit,
          lastRetrievedId,
          truncate,
        });

        return parseNdjsonStreamToArray<TracePublic>(
          streamResponse,
          serialization.TracePublic,
          limit
        );
      },
      (trace) => trace.id
    );

    logger.debug(
      `Fetched ${traces.length} items from annotation queue "${this.name}"`
    );

    return traces;
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
