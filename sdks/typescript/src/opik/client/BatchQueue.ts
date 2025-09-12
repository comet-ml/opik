import { logger } from "@/utils/logger";

const DEFAULT_DEBOUNCE_BATCH_DELAY = 300;
const DEFAULT_BATCH_SIZE = 100;

class ActionQueue<EntityData = object, EntityId = string> {
  private action: (map: Map<EntityId, EntityData>) => Promise<void>;
  public batchSize: number;
  private delay: number;
  private enableBatch: boolean;
  public name: string;
  private timerId: NodeJS.Timeout | null = null;
  private promise: Promise<void> = Promise.resolve();
  public queue = new Map<EntityId, EntityData>();

  constructor({
    action,
    batchSize = DEFAULT_BATCH_SIZE,
    delay,
    enableBatch,
    name = "ActionQueue",
  }: {
    action: (map: Map<EntityId, EntityData>) => Promise<void>;
    batchSize?: number;
    delay: number;
    enableBatch: boolean;
    name?: string;
  }) {
    this.action = action;
    this.batchSize = batchSize;
    this.delay = delay;
    this.enableBatch = enableBatch;
    this.name = name;
  }

  private debounceFlush = () => {
    if (this.timerId) {
      clearTimeout(this.timerId);
    }

    this.timerId = setTimeout(() => this.flush(), this.delay);
  };

  public add = (id: EntityId, entity: EntityData) => {
    this.queue.set(id, entity);

    if (!this.enableBatch) {
      this.flush();
      return;
    }

    // @todo: change to check payload size instead of batch size
    if (this.queue.size >= this.batchSize) {
      this.flush();
      return;
    }

    this.debounceFlush();
  };

  public update = (id: EntityId, updates: Partial<EntityData>) => {
    const entity = this.queue.get(id);

    if (entity) {
      this.queue.set(id, { ...entity, ...updates });
      this.debounceFlush();
    }
  };

  public flush = async () => {
    if (this.queue.size === 0) {
      return this.promise;
    }

    const queue = new Map(this.queue);
    this.queue.clear();

    logger.debug(`Adding ${queue.size} items to ${this.name} promise:`, queue);
    this.promise = this.promise
      .finally(() => {
        logger.debug(`Flushing ${this.name}:`, queue);
        return this.action(queue);
      })
      .catch((error) => {
        logger.error(`Failed to flush ${this.name}:`, error, queue);
      });

    await this.promise;
  };
}

export interface BatchQueueOptions {
  delay?: number;
  enableCreateBatch?: boolean;
  enableUpdateBatch?: boolean;
  enableDeleteBatch?: boolean;
  createBatchSize?: number;
  updateBatchSize?: number;
  deleteBatchSize?: number;
  name?: string;
}

export abstract class BatchQueue<EntityData = object, EntityId = string> {
  private readonly createQueue;
  private readonly updateQueue;
  private readonly deleteQueue;
  private readonly name: string;

  constructor({
    delay = DEFAULT_DEBOUNCE_BATCH_DELAY,
    enableCreateBatch = true,
    enableUpdateBatch = false,
    enableDeleteBatch = true,
    createBatchSize = DEFAULT_BATCH_SIZE,
    updateBatchSize = DEFAULT_BATCH_SIZE,
    deleteBatchSize = DEFAULT_BATCH_SIZE,
    name = "BatchQueue",
  }: BatchQueueOptions = {}) {
    this.name = name;

    this.createQueue = new ActionQueue<EntityData, EntityId>({
      action: async (map) => {
        await this.createEntities(Array.from(map.values()));
      },
      delay,
      enableBatch: enableCreateBatch,
      batchSize: createBatchSize,
      name: `${name}:createQueue`,
    });

    this.updateQueue = new ActionQueue<Partial<EntityData>, EntityId>({
      action: async (map) => {
        await this.createQueue.flush();

        const entities = Array.from(map.entries());
        for (const [id, updates] of entities) {
          await this.updateEntity(id, updates);
        }
      },
      delay,
      enableBatch: enableUpdateBatch,
      batchSize: updateBatchSize,
      name: `${name}:updateQueue`,
    });

    this.deleteQueue = new ActionQueue<void, EntityId>({
      action: async (map) => {
        await this.createQueue.flush();
        await this.updateQueue.flush();

        await this.deleteEntities(Array.from(map.keys()));
      },
      delay,
      enableBatch: enableDeleteBatch,
      batchSize: deleteBatchSize,
      name: `${name}:deleteQueue`,
    });
  }

  protected abstract createEntities(entities: EntityData[]): Promise<void>;
  protected abstract getEntity(id: EntityId): Promise<EntityData | undefined>;
  protected abstract updateEntity(
    id: EntityId,
    updates: Partial<EntityData>
  ): Promise<void>;
  protected abstract deleteEntities(ids: EntityId[]): Promise<void>;
  protected abstract getId(entity: EntityData): EntityId;

  public create = (entity: EntityData) => {
    const id = this.getId(entity);
    this.createQueue.add(id, entity);
  };

  public get = async (id: EntityId) => {
    const entity = this.createQueue.queue.get(id);

    if (entity) {
      // should flush create instead?
      return entity;
    }

    return this.getEntity(id);
  };

  public update = (id: EntityId, updates: Partial<EntityData>) => {
    const entity = this.createQueue.queue.get(id);

    if (entity) {
      this.createQueue.update(id, updates);
      return;
    }

    this.updateQueue.add(id, updates);
  };

  public delete = (id: EntityId) => {
    if (this.createQueue.queue.has(id)) {
      // is it needed to call the delete anyway?
      this.createQueue.queue.delete(id);
      return;
    }

    this.deleteQueue.add(id);
  };

  public flush = async () => {
    await this.createQueue.flush();
    await this.updateQueue.flush();
    await this.deleteQueue.flush();
  };
}
