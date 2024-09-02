import { API_URL } from "@e2e/config";
import { Page } from "@playwright/test";
import { Span } from "./Span";
import { Trace } from "./Trace";

export class FeedbackScore {
  constructor(
    readonly page: Page,
    readonly parent: ScoreParent,
    readonly score: FeedbackScoreData,
  ) {}

  static async create(parent: ScoreParent, score: FeedbackScoreData) {
    const { entity, type } = parent;
    const entityPathSegment = type === "trace" ? "traces" : "spans";

    await entity.page.request.put(
      `${API_URL}${entityPathSegment}/${entity.id}/feedback-scores`,
      { data: score },
    );

    return new FeedbackScore(entity.page, parent, score);
  }

  async destroy() {
    const entityPathSegment = this.parent.type === "trace" ? "traces" : "spans";
    await this.page.request.post(
      `${API_URL}${entityPathSegment}/${this.parent.entity.id}/feedback-scores/delete`,
      { data: { name: this.score.name } },
    );
  }
}

type ScoreParent =
  | { entity: Trace; type: "trace" }
  | { entity: Span; type: "span" };

export type FeedbackScoreData = {
  name: string;
  category_name?: string;
  value: number;
  reason?: string;
  source: "sdk" | "ui";
};
