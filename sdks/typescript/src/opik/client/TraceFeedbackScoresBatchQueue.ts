import { FeedbackScoreBatchItem } from "@/rest_api/api/types/FeedbackScoreBatchItem";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";

type FeedbackScoreId = {
  id: string;
  name: string;
};

export class TraceFeedbackScoresBatchQueue extends BatchQueue<
  FeedbackScoreBatchItem,
  FeedbackScoreId
> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
  ) {
    super({
      delay,
      enableDeleteBatch: false,
      name: "TraceFeedbackScoresBatchQueue",
    });
  }

  protected getId(entity: FeedbackScoreBatchItem) {
    return { id: entity.id, name: entity.name };
  }

  protected async createEntities(scores: FeedbackScoreBatchItem[]) {
    await this.api.traces.scoreBatchOfTraces(
      { scores },
      this.api.requestOptions,
    );
  }

  protected async getEntity(): Promise<FeedbackScoreBatchItem | undefined> {
    throw new Error("Not implemented");
  }

  protected async updateEntity() {
    throw new Error("Not implemented");
  }

  protected async deleteEntities(scoreIds: FeedbackScoreId[]) {
    for (const scoreId of scoreIds) {
      await this.api.traces.deleteTraceFeedbackScore(
        scoreId.id,
        {
          name: scoreId.name,
        },
        this.api.requestOptions,
      );
    }
  }
}
