export const SPAN_1 = {
  name: "e2e-span",
  type: "llm",
  tags: ["test", "e2e", "llm"],
  output: {
    response: "preparing context",
  },
  usage: {
    prompt_tokens: 157,
    completion_tokens: 68,
    total_tokens: 225,
  },
};

export const SPAN_2 = {
  name: "e2e-sub-span",
  type: "general",
  tags: ["test", "general"],
  input: {
    prompt: "What is the capital of France?",
  },
  output: {
    response: "The capital of France is Paris.",
  },
  metadata: {
    test: true,
    version: 1,
    string: "span 2 metadata",
    deep: {
      test: false,
      version: 2,
      string: "e2e",
    },
  },
};
