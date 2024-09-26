import { API_URL } from "@e2e/config";
import { Page } from "@playwright/test";

export class FeedbackDefinition {
  constructor(
    readonly page: Page,
    readonly id: string,
    readonly name: string,
    readonly type: FEEDBACK_DEFINITION_TYPE,
    readonly details:
      | FeedbackDefinitionNumericalDetails
      | FeedbackDefinitionCategoricalDetails,
  ) {}

  static async create(
    page: Page,
    name: string,
    type: FEEDBACK_DEFINITION_TYPE,
    details:
      | FeedbackDefinitionNumericalDetails
      | FeedbackDefinitionCategoricalDetails,
  ) {
    await page.request.post(`${API_URL}feedback-definitions`, {
      data: {
        name,
        type,
        details,
      },
    });

    const result = await page.request.get(`${API_URL}feedback-definitions`, {
      params: { name },
    });
    const {
      content: [feedbackDefinition],
    } = await result.json();

    return new FeedbackDefinition(
      page,
      feedbackDefinition.id,
      name,
      type,
      details,
    );
  }

  static async destroy(page: Page, name: string) {
    const result = await page.request.get(`${API_URL}feedback-definitions`, {
      params: { name },
    });
    const {
      content: [feedbackDefinition],
    } = await result.json();

    await page.request.delete(
      `${API_URL}feedback-definitions/${feedbackDefinition.id}`,
    );
  }

  async destroy() {
    await this.page.request.delete(`${API_URL}feedback-definitions/${this.id}`);
  }
}

export type FEEDBACK_DEFINITION_TYPE = "categorical" | "numerical";

export type FeedbackDefinitionNumericalDetails = {
  min: number;
  max: number;
};

export type FeedbackDefinitionCategoricalDetails = {
  categories: Record<string, number>;
};

export type FeedbackDefinitionData = {
  name: string;
  type: FEEDBACK_DEFINITION_TYPE;
  details:
    | FeedbackDefinitionNumericalDetails
    | FeedbackDefinitionCategoricalDetails;
};
