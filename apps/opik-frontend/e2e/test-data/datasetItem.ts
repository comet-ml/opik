export const DATASET_ITEM_1 = {
  data: {
    input: {
      prompt: "What is the capital of France?",
    },
    expected_output: {
      response: "The capital of France is Paris.",
    },
    metadata: {
      test: true,
      version: 1,
    },
    custom: "some_custom_dataset_id",
  },
  source: "manual",
} as const;

export const DATASET_ITEM_2 = {
  data: {
    input: {
      prompt:
        "Translate the following sentence into Spanish: 'Good morning, how are you?'",
    },
    expected_output: {
      response: "Buenos días, ¿cómo estás?",
    },
  },
  source: "sdk",
} as const;
