export const OPENAI_RENDER_LIMITS = {
  messages: 100,
  toolCallsPerMessage: 50,
  toolArgumentsLength: 50_000,
  toolArgumentsTotalLength: 100_000,
} as const;
