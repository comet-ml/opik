import { beforeEach, describe, expect, it, vi } from "vitest";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import {
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const seed = (state: unknown, version?: number) =>
  localStorage.setItem(
    "PLAYGROUND_STATE",
    JSON.stringify(version === undefined ? { state } : { state, version }),
  );

// The store is a module singleton that hydrates on import, so each case needs a fresh module.
const loadPromptMap = async () => {
  vi.resetModules();
  const { default: usePlaygroundStore } = await import(
    "@/store/PlaygroundStore"
  );
  return usePlaygroundStore.getState().promptMap;
};

describe("PLAYGROUND_STATE hydration", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("puts back a config parameter a stored prompt is missing", async () => {
    // What a workspace has stored after switching to an OpenAI reasoning model and back: the old
    // reconciler cleared topP, and the panel renders Top P only for a config that carries it.
    seed({
      promptIds: ["p1"],
      promptMap: {
        p1: {
          name: "Prompt 1",
          id: "p1",
          messages: [],
          model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
          provider: PROVIDER_TYPE.OPEN_AI,
          configs: {
            temperature: 0.4,
            maxCompletionTokens: 4000,
            frequencyPenalty: 0,
            presencePenalty: 0,
          },
        },
      },
    });

    const configs = (await loadPromptMap()).p1.configs as LLMOpenAIConfigsType;

    expect(configs.topP).toBe(1);
    expect(configs.temperature).toBe(0.4);
  });

  it("hydrates a prompt stored without a config instead of failing the whole state", async () => {
    seed({
      promptIds: ["p1"],
      promptMap: {
        p1: {
          name: "Prompt 1",
          id: "p1",
          messages: [],
          model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
          provider: PROVIDER_TYPE.OPEN_AI,
        },
      },
    });

    const configs = (await loadPromptMap()).p1.configs as LLMOpenAIConfigsType;

    expect(configs.topP).toBe(1);
    expect(configs.maxCompletionTokens).toBe(4000);
  });

  it("never overwrites a value the user chose", async () => {
    seed({
      promptIds: ["p1"],
      promptMap: {
        p1: {
          name: "Prompt 1",
          id: "p1",
          messages: [],
          model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
          provider: PROVIDER_TYPE.OPEN_AI,
          configs: { temperature: 0.4, topP: 0.75 },
        },
      },
    });

    const configs = (await loadPromptMap()).p1.configs as LLMOpenAIConfigsType;

    expect(configs.topP).toBe(0.75);
    expect(configs.temperature).toBe(0.4);
    expect(configs.maxCompletionTokens).toBe(4000);
  });
});

describe("PLAYGROUND_STATE hydration of a prompt loaded from the library", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const EDITED_MESSAGES = [
    { id: "m1", role: LLM_MESSAGE_ROLE.user, content: "Say hi EDITED" },
  ];
  const BLANK_MESSAGES = [
    { id: "m1", role: LLM_MESSAGE_ROLE.user, content: "" },
  ];

  const storedPromptWith = (fields: Record<string, unknown>) => ({
    name: "Prompt 1",
    id: "p1",
    model: "",
    provider: "",
    configs: {},
    loadedChatPromptId: "greeter",
    ...fields,
  });

  it.each([
    {
      stored: "is pinned to a version and has messages",
      fields: { loadedChatPromptVersionId: "v1", messages: EDITED_MESSAGES },
      applied: "v1",
    },
    {
      stored: "follows the latest version",
      fields: { messages: EDITED_MESSAGES },
      applied: undefined,
    },
    {
      stored: "is pinned to a version but has only a blank message",
      fields: { loadedChatPromptVersionId: "v1", messages: BLANK_MESSAGES },
      applied: undefined,
    },
    {
      stored: "already records the version it applied",
      fields: {
        loadedChatPromptVersionId: "v2",
        appliedChatPromptVersionId: "v1",
        messages: EDITED_MESSAGES,
      },
      applied: "v1",
    },
    {
      stored: "has no messages",
      fields: { loadedChatPromptVersionId: "v1" },
      applied: undefined,
    },
  ])(
    "records the applied version only when it can be known: a prompt that $stored",
    async ({ fields, applied }) => {
      seed({ promptIds: ["p1"], promptMap: { p1: storedPromptWith(fields) } });

      const prompt = (await loadPromptMap()).p1;

      expect(prompt.appliedChatPromptVersionId).toBe(applied);
      expect(prompt.messages).toEqual(fields.messages);
    },
  );
});
