export const CATEGORICAL_FEEDBACK_DEFINITION = {
  name: "e2e-ui-categorical",
  type: "categorical",
  details: {
    categories: {
      first: 0,
      second: 1,
      third: 2,
    },
  },
} as const;

export const NUMERICAL_FEEDBACK_DEFINITION = {
  name: "e2e-ui-numerical",
  type: "numerical",
  details: {
    min: 0,
    max: 10,
  },
} as const;
