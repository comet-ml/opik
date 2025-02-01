type CreateEntity = { id: string };

const DEFAULT_DEBOUNCE_BATCH_DELAY = 300;
const DEFAULT_BATCH_SIZE = 100;

class ActionQueue<EntityData = {}> {
  private action: (map: Map<string, EntityData>) => Promise<void>;
  public batchSize: number;
  private delay: number;
  private enableBatch: boolean;
  public name: string;
  private timerId: NodeJS.Timeout | null = null;
  private promise: Promise<void> = Promise.resolve();
  public queue = new Map<string, EntityData>();

  constructor({
    action,
    batchSize = DEFAULT_BATCH_SIZE,
    delay,
    enableBatch,
    name = "ActionQueue",
  }: {
    action: (map: Map<string, EntityData>) => Promise<void>;
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

  public add = (id: string, entity: EntityData) => {
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

  public update = (id: string, updates: Partial<EntityData>) => {
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
    this.promise = this.promise.finally(() => this.action(queue));
    await this.promise;
  };
}

export abstract class BatchQueue<EntityData = {}> {
  private readonly createQueue;
  private readonly updateQueue;
  private readonly deleteQueue;
  private readonly name: string;

  constructor({
    delay = DEFAULT_DEBOUNCE_BATCH_DELAY,
    enableCreateBatch = true,
    enableDeleteBatch = true,
    name = "BatchQueue",
  }: {
    delay?: number;
    enableCreateBatch?: boolean;
    enableDeleteBatch?: boolean;
    name?: string;
  }) {
    this.name = name;

    this.createQueue = new ActionQueue<EntityData>({
      action: async (map) => {
        await this.createEntities(Array.from(map.values()));
      },
      delay,
      enableBatch: enableCreateBatch,
      name: `${name}:createQueue`,
    });

    this.updateQueue = new ActionQueue<Partial<EntityData>>({
      action: async (map) => {
        const entities = Array.from(map.entries());
        for (const [id, updates] of entities) {
          await this.updateEntity(id, updates);
        }
      },
      delay,
      enableBatch: false,
      name: `${name}:updateQueue`,
    });

    this.deleteQueue = new ActionQueue<void>({
      action: async (map) => {
        await this.deleteEntities(Array.from(map.keys()));
      },
      delay,
      enableBatch: enableDeleteBatch,
      name: `${name}:deleteQueue`,
    });
  }

  protected abstract createEntities(entities: EntityData[]): Promise<void>;
  protected abstract getEntity(id: string): Promise<EntityData | undefined>;
  protected abstract updateEntity(
    id: string,
    updates: Partial<EntityData>
  ): Promise<void>;
  protected abstract deleteEntities(ids: string[]): Promise<void>;

  public create = (entity: CreateEntity & EntityData) => {
    this.createQueue.add(entity.id, entity);
  };

  public get = async (id: string) => {
    const entity = this.createQueue.queue.get(id);

    if (entity) {
      // should flush create instead?
      return entity;
    }

    return this.getEntity(id);
  };

  public update = (id: string, updates: Partial<EntityData>) => {
    const entity = this.createQueue.queue.get(id);

    if (entity) {
      this.createQueue.update(id, updates);
      return;
    }

    this.updateQueue.add(id, updates);
  };

  public delete = (id: string) => {
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
