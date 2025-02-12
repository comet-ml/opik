import { FeedbackScoreBatchItem } from "@/rest_api/api/types/FeedbackScoreBatchItem";
import { OpikApiClient } from "@/rest_api/Client";
import { BatchQueue } from "./BatchQueue";

type FeedbackScoreId = {
  id: string;
  name: string;
};

export class SpanFeedbackScoresBatchQueue extends BatchQueue<
  FeedbackScoreBatchItem,
  FeedbackScoreId
> {
  constructor(
    private readonly api: OpikApiClient,
    delay?: number
  ) {
    super({
      delay,
      enableDeleteBatch: false,
      name: "SpanFeedbackScoresBatchQueue",
    });
  }

  protected getId(entity: FeedbackScoreBatchItem) {
    return { id: entity.id, name: entity.name };
  }

  protected async createEntities(scores: FeedbackScoreBatchItem[]) {
    await this.api.spans.scoreBatchOfSpans({ scores });
  }

  protected async getEntity(): Promise<FeedbackScoreBatchItem | undefined> {
    throw new Error("Not implemented");
  }

  protected async updateEntity() {
    throw new Error("Not implemented");
  }

  protected async deleteEntities(scoreIds: FeedbackScoreId[]) {
    for (const scoreId of scoreIds) {
      await this.api.spans.deleteSpanFeedbackScore(scoreId.id, {
        name: scoreId.name,
      });
    }
  }
}
