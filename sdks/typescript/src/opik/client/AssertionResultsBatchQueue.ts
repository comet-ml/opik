import type { AssertionResultBatchEntityType } from "@/rest_api/api/resources/assertionResults/types/AssertionResultBatchEntityType";
import type { AssertionResultBatchItem } from "@/rest_api/api/types/AssertionResultBatchItem";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { BatchQueue } from "./BatchQueue";

type AssertionResultId = {
  entityId: string;
  name: string;
};

export class AssertionResultsBatchQueue extends BatchQueue<
  AssertionResultBatchItem,
  AssertionResultId
> {
  private readonly entityType: AssertionResultBatchEntityType;

  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
    entityType: AssertionResultBatchEntityType = "TRACE"
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: false,
      enableDeleteBatch: false,
      name: `AssertionResultsBatchQueue:${entityType}`,
    });
    this.entityType = entityType;
  }

  protected getId(entity: AssertionResultBatchItem) {
    return { entityId: entity.entityId, name: entity.name };
  }

  protected async createEntities(assertionResults: AssertionResultBatchItem[]) {
    await this.api.assertionResults.storeAssertionsBatch(
      { entityType: this.entityType, assertionResults },
      this.api.requestOptions
    );
  }

  protected async getEntity(): Promise<AssertionResultBatchItem | undefined> {
    throw new Error("Not implemented");
  }

  protected async updateEntity() {
    throw new Error("Not implemented");
  }

  protected async deleteEntities() {
    throw new Error("Not implemented");
  }
}
