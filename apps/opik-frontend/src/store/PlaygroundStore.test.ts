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

describe("PlaygroundStore staleChanges", () => {
  const getOutput = () => usePlaygroundStore.getState().outputMap[PROMPT_ID];
  const getStaleChanges = () => {
    const output = getOutput();
    return "staleChanges" in output ? output.staleChanges : undefined;
  };
  const updatePrompt = (changes: Partial<PlaygroundPromptType>) =>
    usePlaygroundStore.getState().updatePrompt(PROMPT_ID, changes);

  beforeEach(() => {
    const { setPromptMap, updateOutput } = usePlaygroundStore.getState();
    setPromptMap([PROMPT_ID], { [PROMPT_ID]: createPrompt() });
    updateOutput(PROMPT_ID, "", { isLoading: false, value: "Hi!" });
  });

  it.each<[string, Partial<PlaygroundPromptType>, string[]]>([
    [
      "a message edit",
      { messages: [createMessage({ content: "Say bye" })] },
      ["prompt"],
    ],
    [
      "a parameter edit",
      { configs: { ...CONFIGS, temperature: 1 } },
      ["parameters"],
    ],
    [
      "a model switch that resets the parameters",
      {
        model: PROVIDER_MODEL_TYPE.GPT_4O,
        configs: { ...CONFIGS, temperature: 1 },
      },
      ["model"],
    ],
    ["a provider switch", { provider: PROVIDER_TYPE.ANTHROPIC }, ["model"]],
  ])("should record %s", (_, changes, expected) => {
    updatePrompt(changes);

    expect(getStaleChanges()).toEqual(expected);
  });

  it("should add up every kind of change until the next run", () => {
    updatePrompt({ configs: { ...CONFIGS, temperature: 1 } });
    updatePrompt({ model: PROVIDER_MODEL_TYPE.GPT_4O });
    updatePrompt({ configs: { ...CONFIGS, temperature: 0 } });

    expect(getStaleChanges()).toEqual(["parameters", "model"]);
  });

  it("should keep the same output object when an edit adds no new kind of change", () => {
    updatePrompt({ messages: [createMessage({ content: "Say bye" })] });
    const staleOutput = getOutput();

    updatePrompt({ messages: [createMessage({ content: "Say bye!" })] });

    expect(getOutput()).toBe(staleOutput);
  });

  it("should forget the changes once the prompt runs again", () => {
    updatePrompt({ model: PROVIDER_MODEL_TYPE.GPT_4O });

    usePlaygroundStore
      .getState()
      .updateOutput(PROMPT_ID, "", { isLoading: true, value: null });

    expect(getOutput()).toMatchObject({ stale: false });
    expect(getStaleChanges()).toBeUndefined();

    updatePrompt({ configs: { ...CONFIGS, temperature: 1 } });

    expect(getStaleChanges()).toEqual(["parameters"]);
  });

  it("should record the change on every dataset item", () => {
    const { setPromptMap, updateOutput } = usePlaygroundStore.getState();
    setPromptMap([PROMPT_ID], { [PROMPT_ID]: createPrompt() });
    usePlaygroundStore.setState({ outputMap: {} });
    updateOutput(PROMPT_ID, "item-1", { isLoading: false, value: "A" });
    updateOutput(PROMPT_ID, "item-2", { isLoading: false, value: "B" });

    updatePrompt({ configs: { ...CONFIGS, temperature: 1 } });

    const output = getOutput();
    expect("datasetItemMap" in output && output.datasetItemMap).toMatchObject({
      "item-1": { stale: true, staleChanges: ["parameters"] },
      "item-2": { stale: true, staleChanges: ["parameters"] },
    });
  });
});

describe("PlaygroundStore setPromptExperiment", () => {
  const PROMPT_A = "prompt-a";
  const PROMPT_B = "prompt-b";

  const getState = () => usePlaygroundStore.getState();

  beforeEach(() => {
    getState().clearCreatedExperiments();
    getState().setPromptMap([PROMPT_A, PROMPT_B], {
      [PROMPT_A]: { ...createPrompt(), id: PROMPT_A },
      [PROMPT_B]: { ...createPrompt(), id: PROMPT_B },
    });
    usePlaygroundStore.setState({ outputMap: {} });
    getState().setExperimentByPromptId({
      [PROMPT_A]: "experiment-a1",
      [PROMPT_B]: "experiment-b1",
    });
  });

  it("should keep the other prompts on the run they were scored in", () => {
    getState().setPromptExperiment(PROMPT_B, "experiment-b2");

    expect(getState().experimentByPromptId).toEqual({
      [PROMPT_A]: "experiment-a1",
      [PROMPT_B]: "experiment-b2",
    });
    expect(getState().runExperimentIdsByPromptId).toEqual({
      [PROMPT_A]: ["experiment-a1", "experiment-b1"],
      [PROMPT_B]: ["experiment-b2"],
    });

    getState().setPromptExperiment(PROMPT_A, "experiment-a2");

    expect(getState().runExperimentIdsByPromptId).toEqual({
      [PROMPT_A]: ["experiment-a2"],
      [PROMPT_B]: ["experiment-b2"],
    });
  });

  it("should make the re-run prompt's output current again and leave the others stale", () => {
    getState().updatePrompt(PROMPT_A, { model: PROVIDER_MODEL_TYPE.GPT_4O });
    getState().updatePrompt(PROMPT_B, { model: PROVIDER_MODEL_TYPE.GPT_4O });

    getState().setPromptExperiment(PROMPT_B, "experiment-b2");

    expect(getState().outputMap[PROMPT_A]).toMatchObject({ stale: true });
    expect(getState().outputMap[PROMPT_B]).toMatchObject({ stale: false });
  });

  it("should rank every prompt together again after a full run", () => {
    getState().setPromptExperiment(PROMPT_B, "experiment-b2");

    getState().setExperimentByPromptId({
      [PROMPT_A]: "experiment-a3",
      [PROMPT_B]: "experiment-b3",
    });

    expect(getState().runExperimentIdsByPromptId).toEqual({});
  });
});
