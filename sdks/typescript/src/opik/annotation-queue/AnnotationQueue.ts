import { OpikClient } from "@/client/Client";
import {
  AnnotationQueuePublic,
  AnnotationQueuePublicScope,
  TracePublic,
  TraceThread,
} from "@/rest_api/api";
import { splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import {
  AnnotationQueueScopeMismatchError,
  AnnotationQueueItemMissingIdError,
} from "./errors";

const ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE = 1000;

export interface AnnotationQueueData {
  id: string;
  name: string;
  projectId: string;
  scope: AnnotationQueuePublicScope;
  description?: string;
  instructions?: string;
  commentsEnabled?: boolean;
  feedbackDefinitionNames?: string[];
  itemsCount?: number;
}

export interface AnnotationQueueUpdateOptions {
  name?: string;
  description?: string;
  instructions?: string;
  commentsEnabled?: boolean;
  feedbackDefinitionNames?: string[];
}

export class AnnotationQueue {
  public readonly id: string;
  public readonly name: string;
  public readonly projectId: string;
  public readonly scope: AnnotationQueuePublicScope;
  public readonly description?: string;
  public readonly instructions?: string;
  public readonly commentsEnabled?: boolean;
  public readonly feedbackDefinitionNames?: string[];

  private _itemsCount?: number;

  constructor(
    data: AnnotationQueueData | AnnotationQueuePublic,
    private opik: OpikClient
  ) {
    this.id = data.id!;
    this.name = data.name;
    this.projectId = data.projectId;
    this.scope = data.scope;
    this.description = data.description;
    this.instructions = data.instructions;
    this.commentsEnabled = data.commentsEnabled;
    this.feedbackDefinitionNames = data.feedbackDefinitionNames;
    this._itemsCount = data.itemsCount;
  }

  /**
   * The total number of items in the queue.
   * If the count is not cached locally, it will be fetched from the backend.
   */
  public get itemsCount(): number | undefined {
    return this._itemsCount;
  }

  /**
   * Refresh the items count from the backend.
   */
  public async refreshItemsCount(): Promise<number | undefined> {
    const queueInfo = await this.opik.api.annotationQueues.getAnnotationQueueById(this.id);
    this._itemsCount = queueInfo.itemsCount;
    return this._itemsCount;
  }

  /**
   * Update the annotation queue properties.
   *
   * @param options - The properties to update
   */
  public async update(options: AnnotationQueueUpdateOptions): Promise<void> {
    logger.debug(`Updating annotation queue "${this.name}"`, options);

    await this.opik.api.annotationQueues.updateAnnotationQueue(this.id, options);

    logger.debug(`Successfully updated annotation queue "${this.name}"`);
  }

  /**
   * Delete this annotation queue.
   */
  public async delete(): Promise<void> {
    logger.debug(`Deleting annotation queue "${this.name}"`);

    await this.opik.api.annotationQueues.deleteAnnotationQueueBatch({
      ids: [this.id],
    });

    logger.debug(`Successfully deleted annotation queue "${this.name}"`);
  }

  private requireScope(expected: AnnotationQueuePublicScope, itemType: string): void {
    if (this.scope !== expected) {
      throw new AnnotationQueueScopeMismatchError(itemType, this.scope);
    }
  }

  private extractTraceIds(traces: TracePublic[]): string[] {
    return traces.map((trace, index) => {
      if (!trace.id) {
        throw new AnnotationQueueItemMissingIdError("trace", index);
      }
      return trace.id;
    });
  }

  private extractThreadIds(threads: TraceThread[]): string[] {
    return threads.map((thread, index) => {
      if (!thread.threadModelId) {
        throw new AnnotationQueueItemMissingIdError("thread", index);
      }
      return thread.threadModelId;
    });
  }

  private async addItemsBatch(ids: string[]): Promise<void> {
    await this.opik.api.annotationQueues.addItemsToAnnotationQueue(this.id, {
      body: { ids },
    });
    logger.debug(`Successfully added ${ids.length} items to annotation queue`);
  }

  private async removeItemsBatch(ids: string[]): Promise<void> {
    await this.opik.api.annotationQueues.removeItemsFromAnnotationQueue(this.id, {
      body: { ids },
    });
    logger.debug(`Successfully removed ${ids.length} items from annotation queue`);
  }

  /**
   * Add trace objects to the annotation queue.
   *
   * @param traces - A list of traces to add. Accepts TracePublic objects (from searchTraces()).
   * @throws AnnotationQueueScopeMismatchError - If the queue scope is not 'trace'.
   * @throws AnnotationQueueItemMissingIdError - If any trace object has no id.
   */
  public async addTraces(traces: TracePublic[]): Promise<void> {
    this.requireScope("trace", "traces");

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

    this._itemsCount = undefined;
  }

  /**
   * Add thread objects to the annotation queue.
   *
   * @param threads - A list of TraceThread objects to add.
   * @throws AnnotationQueueScopeMismatchError - If the queue scope is not 'thread'.
   * @throws AnnotationQueueItemMissingIdError - If any thread object has no threadModelId.
   */
  public async addThreads(threads: TraceThread[]): Promise<void> {
    this.requireScope("thread", "threads");

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

    this._itemsCount = undefined;
  }

  /**
   * Remove trace objects from the annotation queue.
   *
   * @param traces - A list of traces to remove. Accepts TracePublic objects (from searchTraces()).
   * @throws AnnotationQueueScopeMismatchError - If the queue scope is not 'trace'.
   * @throws AnnotationQueueItemMissingIdError - If any trace object has no id.
   */
  public async removeTraces(traces: TracePublic[]): Promise<void> {
    this.requireScope("trace", "traces");

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

    this._itemsCount = undefined;
  }

  /**
   * Remove thread objects from the annotation queue.
   *
   * @param threads - A list of TraceThread objects to remove.
   * @throws AnnotationQueueScopeMismatchError - If the queue scope is not 'thread'.
   * @throws AnnotationQueueItemMissingIdError - If any thread object has no threadModelId.
   */
  public async removeThreads(threads: TraceThread[]): Promise<void> {
    this.requireScope("thread", "threads");

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

    this._itemsCount = undefined;
  }
}
