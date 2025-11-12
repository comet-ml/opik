export enum FEEDBACK_DEFINITION_TYPE {
  categorical = "categorical",
  numerical = "numerical",
  boolean = "boolean",
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

export interface BooleanFeedbackDefinition {
  details: {
    true_label: string;
    false_label: string;
  };
  type: FEEDBACK_DEFINITION_TYPE.boolean;
}

export type CreateFeedbackDefinition = {
  name: string;
  description?: string;
} & (
  | CategoricalFeedbackDefinition
  | NumericalFeedbackDefinition
  | BooleanFeedbackDefinition
);

export type FeedbackDefinition = CreateFeedbackDefinition & {
  id: string;
  created_at: string;
  last_updated_at: string;
};
