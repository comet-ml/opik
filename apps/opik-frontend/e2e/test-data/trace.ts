export const TRACE_1 = {
  name: "e2e-trace",
  tags: ["test", "e2e"],
  input: {
    prompt: "What is the capital of France?",
  },
  output: {
    response: "The capital of France is Paris.",
  },
  metadata: {
    test: true,
    version: 1,
    string: "trace 1 metadata",
    deep: {
      test: false,
      version: 2,
      string: "e2e",
    },
  },
};

export const TRACE_2 = {
  name: "e2e-trace-second",
  tags: ["test", "unit"],
  input: {
    prompt:
      "Translate the following sentence into Spanish: 'Good morning, how are you?'",
  },
  start_time: "2024-10-02T12:06:16.346Z",
  end_time: "2024-10-02T12:06:28.846Z",
};

export const TRACE_SCORE = {
  name: "hallucination-trace",
  source: "sdk",
  value: 1,
};
