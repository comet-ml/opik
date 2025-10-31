export enum FEEDBACK_DEFINITION_TYPE {
  categorical = "categorical",
  numerical = "numerical",
}

export interface CategoricalFeedbackDefinition {
  details: {
    categories: Record<string, number>;
  };
  type: FEEDBACK_DEFINITION_TYPE.categorical;
}

export interface NumericalFeedbackDefinition {
  details: {
    max: number;
    min: number;
  };
  type: FEEDBACK_DEFINITION_TYPE.numerical;
}

export type CreateFeedbackDefinition = {
  name: string;
  description?: string;
} & (CategoricalFeedbackDefinition | NumericalFeedbackDefinition);

export type FeedbackDefinition = CreateFeedbackDefinition & {
  id: string;
  created_at: string;
  last_updated_at: string;
};
