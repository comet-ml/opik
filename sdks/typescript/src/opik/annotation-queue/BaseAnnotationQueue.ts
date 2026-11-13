import { OpikClient } from "@/client/Client";
import {
  AnnotationQueuePublic,
  AnnotationQueuePublicScope,
} from "@/rest_api/api";
import { logger } from "@/utils/logger";

export const ANNOTATION_QUEUE_ITEMS_MAX_BATCH_SIZE = 1000;

export interface AnnotationQueueData {
  id: string;
  name: string;
  projectId: string;
  scope?: AnnotationQueuePublicScope;
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

export abstract class BaseAnnotationQueue {
  public readonly id: string;
  public readonly name: string;
  public readonly projectId: string;
  public readonly scope: AnnotationQueuePublicScope;
  public readonly description?: string;
  public readonly instructions?: string;
  public readonly commentsEnabled?: boolean;
  public readonly feedbackDefinitionNames?: string[];

  static readonly SCOPE: AnnotationQueuePublicScope;

  constructor(
    data: AnnotationQueueData | AnnotationQueuePublic,
    protected opik: OpikClient,
  ) {
    this.id = data.id!;
    this.name = data.name;
    this.projectId = data.projectId;
    this.scope = data.scope ?? (this.constructor as typeof BaseAnnotationQueue).SCOPE;
    this.description = data.description;
    this.instructions = data.instructions;
    this.commentsEnabled = data.commentsEnabled;
    this.feedbackDefinitionNames = data.feedbackDefinitionNames;
  }

  public async getItemsCount(): Promise<number | undefined> {
    const queueInfo = await this.opik.api.annotationQueues.getAnnotationQueueById(this.id);
    return queueInfo.itemsCount;
  }

  public async update(options: AnnotationQueueUpdateOptions): Promise<void> {
    logger.debug(`Updating annotation queue "${this.name}"`, options);

    await this.opik.api.annotationQueues.updateAnnotationQueue(this.id, options);

    logger.debug(`Successfully updated annotation queue "${this.name}"`);
  }

  public async delete(): Promise<void> {
    logger.debug(`Deleting annotation queue "${this.name}"`);

    await this.opik.api.annotationQueues.deleteAnnotationQueueBatch({
      ids: [this.id],
    });

    logger.debug(`Successfully deleted annotation queue "${this.name}"`);
  }

  protected async addItemsBatch(ids: string[]): Promise<void> {
    await this.opik.api.annotationQueues.addItemsToAnnotationQueue(this.id, {
      body: { ids },
    });
    logger.debug(`Successfully added ${ids.length} items to annotation queue`);
  }

  protected async removeItemsBatch(ids: string[]): Promise<void> {
    await this.opik.api.annotationQueues.removeItemsFromAnnotationQueue(this.id, {
      body: { ids },
    });
    logger.debug(`Successfully removed ${ids.length} items from annotation queue`);
  }
}
