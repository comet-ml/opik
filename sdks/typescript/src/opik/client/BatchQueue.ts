type CreateEntity = { id: string };

const DEFAULT_DEBOUNCE_BATCH_DELAY = 300;

export abstract class BatchQueue<EntityData = {}> {
  private createQueue = new Map<string, EntityData>();
  private createTimerId: NodeJS.Timeout | null = null;
  private deleteQueue = new Set<string>();
  private deleteTimerId: NodeJS.Timeout | null = null;
  private createFlushChain = Promise.resolve();
  private deleteFlushChain = Promise.resolve();
  private updateFlushChain = Promise.resolve();
  private readonly delay: number;
  private readonly enableCreateBatch: boolean;
  private readonly enableDeleteBatch: boolean;
  private readonly name: string;
  constructor({
    delay = DEFAULT_DEBOUNCE_BATCH_DELAY,
    name = "",
    enableCreateBatch = true,
    enableDeleteBatch = true,
  }: {
    delay?: number;
    name?: string;
    enableCreateBatch?: boolean;
    enableDeleteBatch?: boolean;
  }) {
    this.delay = delay;
    this.name = name;
    this.enableCreateBatch = enableCreateBatch;
    this.enableDeleteBatch = enableDeleteBatch;
  }

  protected abstract createEntities(entities: EntityData[]): Promise<void>;
  protected abstract getEntity(id: string): Promise<EntityData | undefined>;
  protected abstract updateEntity(
    id: string,
    updates: Partial<EntityData>
  ): Promise<void>;
  protected abstract deleteEntities(ids: string[]): Promise<void>;

  private resetCreateFlushTimer = () => {
    if (this.createTimerId) {
      clearTimeout(this.createTimerId);
    }
    this.createTimerId = setTimeout(() => this.flushCreate(), this.delay);
  };

  private resetDeleteFlushTimer = () => {
    if (this.deleteTimerId) {
      clearTimeout(this.deleteTimerId);
    }
    this.deleteTimerId = setTimeout(() => this.flushDelete(), this.delay);
  };

  public create = (entity: CreateEntity & EntityData) => {
    if (!this.enableCreateBatch) {
      this.createEntities([entity]);
      return;
    }

    if (this.createQueue.has(entity.id)) {
      // error, override or ignore?
      // throw new Error(`Entity with id ${entity.id} already exists`);
    }

    this.createQueue.set(entity.id, entity);
    this.resetCreateFlushTimer();
  };

  public get = async (id: string) => {
    const entity = this.createQueue.get(id);

    if (entity) {
      return entity;
    }

    return this.getEntity(id);
  };

  public update = (id: string, updates: Partial<EntityData>) => {
    const entity = this.createQueue.get(id);

    if (entity) {
      this.createQueue.set(id, { ...entity, ...updates });
      this.resetCreateFlushTimer();
      return;
    }

    this.updateFlushChain = this.updateFlushChain.finally(() =>
      this.updateEntity(id, updates)
    );
  };

  public delete = (id: string) => {
    if (this.createQueue.has(id)) {
      // is it needed to call the delete anyway?
      this.createQueue.delete(id);
      return;
    }

    if (!this.enableDeleteBatch) {
      this.deleteEntities([id]);
      return;
    }

    this.deleteQueue.add(id);
    this.resetDeleteFlushTimer();
  };

  protected flushCreate = async (createQueue = this.createQueue) => {
    if (createQueue.size === 0) {
      return this.createFlushChain;
    }

    const entities = Array.from(createQueue.values());
    createQueue.clear();
    this.createFlushChain = this.createFlushChain.finally(() => {
      return this.createEntities(entities);
    });

    return this.createFlushChain;
  };

  protected flushDelete = async (deleteQueue = this.deleteQueue) => {
    if (deleteQueue.size === 0) {
      return this.deleteFlushChain;
    }

    const ids = Array.from(deleteQueue);
    deleteQueue.clear();
    this.deleteFlushChain = this.deleteFlushChain.finally(() => {
      return this.deleteEntities(ids);
    });

    return this.deleteFlushChain;
  };

  public flush = async () => {
    const createQueue = new Map(this.createQueue);
    const deleteQueue = new Set(this.deleteQueue);

    this.createQueue.clear();
    this.deleteQueue.clear();

    await this.flushCreate(createQueue);
    await this.flushDelete(deleteQueue);
    await this.updateFlushChain;
  };
}
