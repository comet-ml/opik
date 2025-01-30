type CreateEntity = { id: string };

const DEFAULT_DELAY = 300;

export abstract class BatchQueue<EntityData = {}> {
  private createQueue = new Map<string, EntityData>();
  private createTimerId: NodeJS.Timeout | null = null;

  constructor(private readonly delay = DEFAULT_DELAY) {}

  protected abstract createEntities(entities: EntityData[]): Promise<void>;
  protected abstract getEntity(id: string): Promise<EntityData | undefined>;
  protected abstract updateEntity(
    id: string,
    updates: Partial<EntityData>
  ): Promise<void>;
  protected abstract deleteEntities(ids: string[]): Promise<void>;

  public create = (entity: CreateEntity & EntityData) => {
    if (this.createQueue.has(entity.id)) {
      throw new Error(`Entity with id ${entity.id} already exists`);
    }

    this.createQueue.set(entity.id, entity);

    if (this.createTimerId) {
      clearTimeout(this.createTimerId);
    }
    this.createTimerId = setTimeout(() => this.flush(), this.delay);
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
      return this.createQueue.set(id, { ...entity, ...updates });
    }

    this.updateEntity(id, updates);
  };

  public delete = (id: string) => {
    if (this.createQueue.has(id)) {
      // is it needed to call the delete anyway?
      return this.createQueue.delete(id);
    }

    this.deleteEntities([id]);
  };

  public flush = async () => {
    const entities = Array.from(this.createQueue.values());
    await this.createEntities(entities);
    this.createQueue.clear();
  };
}
