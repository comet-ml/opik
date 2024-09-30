export const DATASET_ITEM_1 = {
  input: {
    prompt: "What is the capital of France?",
  },
  expected_output: {
    response: "The capital of France is Paris.",
  },
} as const;

export const DATASET_ITEM_2 = {
  input: {
    prompt:
      "Translate the following sentence into Spanish: 'Good morning, how are you?'",
  },
  expected_output: {
    response: "Buenos días, ¿cómo estás?",
  },
} as const;
