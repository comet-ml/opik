import { beforeEach, describe, expect, it } from "vitest";

import usePlaygroundStore from "@/store/PlaygroundStore";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { PlaygroundPromptType } from "@/types/playground";
import {
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const PROMPT_ID = "prompt-1";

const CONFIGS: LLMOpenAIConfigsType = {
  temperature: 0.4,
  maxCompletionTokens: 4000,
  topP: 1,
  frequencyPenalty: 0,
  presencePenalty: 0,
};

const createMessage = (overrides: Partial<LLMMessage> = {}): LLMMessage => ({
  id: "message-1",
  role: LLM_MESSAGE_ROLE.user,
  content: "Say hi",
  promptId: "library-text-1",
  promptVersionId: "library-text-1-v1",
  ...overrides,
});

const createPrompt = (): PlaygroundPromptType => ({
  id: PROMPT_ID,
  name: "Prompt",
  messages: [createMessage()],
  model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: CONFIGS,
});

const isOutputStale = () => {
  const output = usePlaygroundStore.getState().outputMap[PROMPT_ID];
  return "stale" in output && output.stale;
};

describe("PlaygroundStore updatePrompt", () => {
  beforeEach(() => {
    const prompt = createPrompt();
    const { setPromptMap, updateOutput } = usePlaygroundStore.getState();
    setPromptMap([PROMPT_ID], { [PROMPT_ID]: prompt });
    updateOutput(PROMPT_ID, "", { isLoading: false, value: "Hi!" });
  });

  it.each<[string, Partial<PlaygroundPromptType>]>([
    ["message text", { messages: [createMessage({ content: "Say bye" })] }],
    [
      "message role",
      { messages: [createMessage({ role: LLM_MESSAGE_ROLE.system })] },
    ],
    [
      "message count",
      { messages: [createMessage(), createMessage({ id: "message-2" })] },
    ],
    ["model", { model: PROVIDER_MODEL_TYPE.GPT_4O }],
    ["configs", { configs: { ...CONFIGS, temperature: 1 } }],
  ])("should mark the output stale when the %s changes", (_, changes) => {
    usePlaygroundStore.getState().updatePrompt(PROMPT_ID, changes);

    expect(isOutputStale()).toBe(true);
  });

  it.each<[string, Partial<PlaygroundPromptType>]>([
    [
      "the loaded prompt re-applies the same messages with new ids",
      { messages: [createMessage({ id: "message-reloaded" })] },
    ],
    [
      "the prompt is linked to the library",
      { loadedChatPromptId: "library-1", loadedChatPromptVersionId: "v-1" },
    ],
    [
      "a message is unlinked from a deleted library prompt",
      {
        messages: [
          createMessage({ promptId: undefined, promptVersionId: undefined }),
        ],
      },
    ],
    [
      "a one-off flag is cleared",
      { messages: [createMessage({ autoImprove: false })] },
    ],
    ["a setting is committed unchanged", { configs: { ...CONFIGS } }],
  ])("should keep the output when %s", (_, changes) => {
    usePlaygroundStore.getState().updatePrompt(PROMPT_ID, changes);

    expect(isOutputStale()).toBe(false);
  });
});
