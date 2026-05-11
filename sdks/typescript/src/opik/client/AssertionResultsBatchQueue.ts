import type { AssertionResultBatchEntityType } from "@/rest_api/api/resources/assertionResults/types/AssertionResultBatchEntityType";
import type { AssertionResultBatchItem } from "@/rest_api/api/types/AssertionResultBatchItem";
import type { FeedbackScoreBatchItem } from "@/rest_api/api/types/FeedbackScoreBatchItem";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { OpikApiError } from "@/rest_api/errors/OpikApiError";
import { logger } from "@/utils/logger";
import { BatchQueue } from "./BatchQueue";

type AssertionResultId = {
  entityId: string;
  name: string;
};

const LEGACY_SUITE_ASSERTION_CATEGORY = "suite_assertion";

export class AssertionResultsBatchQueue extends BatchQueue<
  AssertionResultBatchItem,
  AssertionResultId
> {
  private useLegacyFallback = false;

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
    if (this.useLegacyFallback) {
      await this.writeViaLegacyFeedbackScores(assertionResults);
      return;
    }

    try {
      await this.api.assertionResults.storeAssertionsBatch(
        { entityType: this.entityType, assertionResults },
        this.api.requestOptions
      );
    } catch (error) {
      if (
        error instanceof OpikApiError &&
        error.statusCode === 404 &&
        this.entityType === "TRACE"
      ) {
        this.useLegacyFallback = true;
        logger.warn(
          "Opik backend does not support PUT /v1/private/assertion-results yet — falling back to the legacy feedback-scores path with categoryName=\"suite_assertion\". Upgrade the Opik backend to a version that includes OPIK-6048 to enable native assertion-results ingestion."
        );
        await this.writeViaLegacyFeedbackScores(assertionResults);
        return;
      }
      throw error;
    }
  }

  private async writeViaLegacyFeedbackScores(
    assertionResults: AssertionResultBatchItem[]
  ): Promise<void> {
    const scores: FeedbackScoreBatchItem[] = assertionResults.map((item) => ({
      id: item.entityId,
      name: item.name,
      value: item.status === "passed" ? 1 : 0,
      categoryName: LEGACY_SUITE_ASSERTION_CATEGORY,
      reason: item.reason,
      source: item.source,
      projectName: item.projectName,
      projectId: item.projectId,
    }));

    await this.api.traces.scoreBatchOfTraces(
      { scores },
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
