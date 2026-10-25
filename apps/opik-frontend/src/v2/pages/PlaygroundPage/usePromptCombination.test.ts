import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import usePromptCombination from "./usePromptCombination";

const PROMPT_ID = "prompt-1";

const mocks = vi.hoisted(() => ({
  runStreaming: vi.fn(),
  updateOutput: vi.fn(),
}));

vi.mock("@/store/PlaygroundStore", () => ({
  default: { getState: () => ({ isRunningMap: { [PROMPT_ID]: true } }) },
  getExperimentNamesForPrompts: () => ({}),
  usePromptIds: () => [],
  usePromptMap: () => ({}),
  useUpdateOutput: () => mocks.updateOutput,
}));
vi.mock("@/api/playground/useCompletionProxyStreaming", () => ({
  default: () => mocks.runStreaming,
}));
vi.mock("@/v2/pages/PlaygroundPage/useHydrateDatasetItemData", () => ({
  useHydrateDatasetItemData: () => async () => ({}),
}));
vi.mock("@/v2/pages/PlaygroundPage/useHydratePromptMetadata", () => ({
  useHydratePromptMetadata: () => async () => undefined,
}));

const prompt: PlaygroundPromptType = {
  id: PROMPT_ID,
  name: "Prompt",
  model: PROVIDER_MODEL_TYPE.GPT_4O,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  messages: [{ id: "message-1", role: LLM_MESSAGE_ROLE.user, content: "Hi" }],
};

const streamedRun = (
  overrides: Partial<RunStreamingReturn>,
): RunStreamingReturn => ({
  result: "",
  startTime: "",
  endTime: "",
  usage: null,
  choices: null,
  finishReason: null,
  providerError: null,
  opikError: null,
  pythonProxyError: null,
  errorStatus: null,
  actualModel: null,
  actualProvider: null,
  ...overrides,
});

const processRun = async (
  run: RunStreamingReturn,
  promptToRun: PlaygroundPromptType = prompt,
) => {
  mocks.runStreaming.mockResolvedValue(run);
  const log = vi.fn();
  const { result } = renderHook(() =>
    usePromptCombination({
      workspaceName: "workspace",
      selectedRuleIds: null,
      addAbortController: vi.fn(),
      deleteAbortController: vi.fn(),
      throttlingSeconds: 0,
    }),
  );

  await result.current.processCombination(
    { prompt: promptToRun },
    { log, finishLogging: vi.fn() },
  );

  return log;
};

describe("usePromptCombination", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it.each([
    [
      429,
      "Rate limit reached for gpt-4o on requests per min (RPM): Limit 3.",
      "Rate limit reached",
    ],
    [402, "You exceeded your current quota.", "Out of credits"],
  ])(
    "logs a streamed %i run and stores the provider message with its hint",
    async (errorStatus, message, title) => {
      const log = await processRun(
        streamedRun({ errorStatus, opikError: message }),
      );

      expect(log).toHaveBeenCalledWith(
        expect.objectContaining({ errorStatus, opikError: message }),
      );
      expect(mocks.updateOutput).toHaveBeenLastCalledWith(PROMPT_ID, {
        isLoading: false,
        error: message,
        errorHint: expect.objectContaining({ title }),
      });
    },
  );

  it("keeps the provider's error and suggests a User message when a System-only prompt is rejected with a 400", async () => {
    const systemOnlyPrompt: PlaygroundPromptType = {
      ...prompt,
      model: PROVIDER_MODEL_TYPE.CLAUDE_OPUS_5_5,
      provider: PROVIDER_TYPE.ANTHROPIC,
      messages: [
        {
          id: "message-1",
          role: LLM_MESSAGE_ROLE.system,
          content: "What parameters do I send you?",
        },
      ],
    };

    await processRun(
      streamedRun({
        errorStatus: 400,
        providerError: "messages: Field required",
      }),
      systemOnlyPrompt,
    );

    expect(mocks.updateOutput).toHaveBeenLastCalledWith(PROMPT_ID, {
      isLoading: false,
      error: "messages: Field required",
      errorHint: {
        action:
          "This model needs a User message. Add one with + Message, then run again.",
      },
    });
  });

  it("clears the previous run's error and hint when a new run starts", async () => {
    await processRun(streamedRun({ result: "Hello" }));

    expect(mocks.updateOutput).toHaveBeenNthCalledWith(
      1,
      PROMPT_ID,
      expect.objectContaining({
        isLoading: true,
        error: undefined,
        errorHint: undefined,
      }),
    );
  });
});
