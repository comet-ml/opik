type CreateEntity = { id: string };

const DEFAULT_DEBOUNCE_BATCH_DELAY = 300;

export abstract class BatchQueue<EntityData = {}> {
  private createQueue = new Map<string, EntityData>();
  private createTimerId: NodeJS.Timeout | null = null;
  private deleteQueue = new Set<string>();
  private deleteTimerId: NodeJS.Timeout | null = null;
  private readonly delay: number;
  private readonly enableCreateBatch: boolean;
  private readonly enableDeleteBatch: boolean;

  constructor({
    delay = DEFAULT_DEBOUNCE_BATCH_DELAY,
    enableCreateBatch = true,
    enableDeleteBatch = true,
  }: {
    delay?: number;
    enableCreateBatch?: boolean;
    enableDeleteBatch?: boolean;
  }) {
    this.delay = delay;
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

    if (this.createTimerId) {
      clearTimeout(this.createTimerId);
    }
    this.createTimerId = setTimeout(() => this.flushCreate(), this.delay);
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
      return;
    }

    this.updateEntity(id, updates);
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

    if (this.deleteTimerId) {
      clearTimeout(this.deleteTimerId);
    }
    this.deleteTimerId = setTimeout(() => this.flushDelete(), this.delay);
  };

  protected flushCreate = async (createQueue = this.createQueue) => {
    if (createQueue.size === 0) {
      return;
    }

    const entities = Array.from(createQueue.values());
    createQueue.clear();
    await this.createEntities(entities);
  };

  protected flushDelete = async (deleteQueue = this.deleteQueue) => {
    if (deleteQueue.size === 0) {
      return;
    }

    const ids = Array.from(deleteQueue);
    deleteQueue.clear();
    await this.deleteEntities(ids);
  };

  public flush = async () => {
    const createQueue = new Map(this.createQueue);
    const deleteQueue = new Set(this.deleteQueue);

    this.createQueue.clear();
    this.deleteQueue.clear();

    await this.flushCreate(createQueue);
    await this.flushDelete(deleteQueue);
  };
}
