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
  expected_output: {
    response: "Buenos días, ¿cómo estás?",
  },
};
