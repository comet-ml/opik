export const CATEGORICAL_FEEDBACK_DEFINITION = {
  name: "e2e-ui-categorical",
  type: "categorical",
  details: {
    categories: {
      first: 0.0000012,
      second: -2,
      third: 333,
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

export const CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED = {
  name: "e2e-ui-categorical_modified",
  type: "categorical",
  details: {
    categories: {
      fourth: 4,
      fifth: 5,
    },
  },
} as const;

export const NUMERICAL_FEEDBACK_DEFINITION_MODIFIED = {
  name: "e2e-ui-numerical_modified",
  type: "numerical",
  details: {
    min: 1,
    max: 100,
  },
} as const;
