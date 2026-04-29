import type { AssertionResultBatchEntityType } from "@/rest_api/api/resources/assertionResults/types/AssertionResultBatchEntityType";
import type { AssertionResultBatchItem } from "@/rest_api/api/types/AssertionResultBatchItem";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { OpikApiError } from "@/rest_api/errors/OpikApiError";
import { logger } from "@/utils/logger";
import { BatchQueue } from "./BatchQueue";

type AssertionResultId = {
  entityId: string;
  name: string;
};

export class AssertionResultsBatchQueue extends BatchQueue<
  AssertionResultBatchItem,
  AssertionResultId
> {
  private unsupportedEndpointWarned = false;

  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
    private readonly entityType: AssertionResultBatchEntityType = "TRACE"
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: false,
      enableDeleteBatch: false,
      name: `AssertionResultsBatchQueue:${entityType}`,
    });
  }

  protected getId(entity: AssertionResultBatchItem) {
    return { entityId: entity.entityId, name: entity.name };
  }

  protected async createEntities(assertionResults: AssertionResultBatchItem[]) {
    try {
      await this.api.assertionResults.storeAssertionsBatch(
        { entityType: this.entityType, assertionResults },
        this.api.requestOptions
      );
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        if (!this.unsupportedEndpointWarned) {
          this.unsupportedEndpointWarned = true;
          logger.warn(
            "Opik backend does not support PUT /v1/private/assertion-results — suite assertion results will not be persisted. Upgrade your self-hosted Opik backend to a version that includes OPIK-6048."
          );
        }
        return;
      }
      throw error;
    }
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
