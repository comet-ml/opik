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
  ])(
    "records the applied version only when it can be known: a prompt that $stored",
    async ({ fields, applied }) => {
      seed({ promptIds: ["p1"], promptMap: { p1: storedPromptWith(fields) } });

      const prompt = (await loadPromptMap()).p1;

      expect(prompt.appliedChatPromptVersionId).toBe(applied);
      expect(prompt.messages).toEqual(fields.messages);
    },
  );

  it("does not record an applied version in state stored by this version, where a pick can still be loading", async () => {
    seed({
      promptIds: ["p1"],
      promptMap: {
        p1: storedPromptWith({
          loadedChatPromptVersionId: "v2",
          messages: EDITED_MESSAGES,
        }),
      },
      appliedChatPromptVersionsBackfilled: true,
    });

    const prompt = (await loadPromptMap()).p1;

    expect(prompt.appliedChatPromptVersionId).toBeUndefined();
    expect(prompt.messages).toEqual(EDITED_MESSAGES);
  });

  it("stores that the backfill ran, so the next load skips it", async () => {
    seed({ promptIds: [], promptMap: {} });

    await loadPromptMap();

    const stored = JSON.parse(localStorage.getItem("PLAYGROUND_STATE")!);
    expect(stored.state.appliedChatPromptVersionsBackfilled).toBe(true);
  });
});

describe("PLAYGROUND_STATE hydration of entries the playground can't render", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  const IMAGE_MESSAGE = {
    id: "m1",
    role: LLM_MESSAGE_ROLE.user,
    content: [
      { type: "text", text: "Describe this" },
      { type: "image_url", image_url: { url: "https://example.com/cat.png" } },
    ],
  };

  const storedPrompt = (id: string, fields: Record<string, unknown> = {}) => ({
    name: id,
    id,
    model: "",
    provider: "",
    configs: {},
    messages: [IMAGE_MESSAGE],
    ...fields,
  });

  const loadState = async () => {
    vi.resetModules();
    const { default: usePlaygroundStore } = await import(
      "@/store/PlaygroundStore"
    );
    return usePlaygroundStore.getState();
  };

  it("drops a listed id that has no saved prompt", async () => {
    seed({
      promptIds: ["p1", "missing"],
      promptMap: { p1: storedPrompt("p1") },
    });

    const { promptIds, promptMap } = await loadState();

    expect(promptIds).toEqual(["p1"]);
    expect(promptMap).toEqual({ p1: storedPrompt("p1") });
  });

  it("drops a saved prompt that isn't listed", async () => {
    seed({
      promptIds: ["p1"],
      promptMap: { p1: storedPrompt("p1"), unlisted: { model: "gpt-4o" } },
    });

    const { promptIds, promptMap } = await loadState();

    expect(promptIds).toEqual(["p1"]);
    expect(promptMap).toEqual({ p1: storedPrompt("p1") });
  });

  it.each<[string, Record<string, unknown>]>([
    ["messages that aren't a list", { messages: "Say hi" }],
    ["no messages", { messages: undefined }],
    ["a message that is null", { messages: [null] }],
    [
      "a message whose content is null",
      { messages: [{ ...IMAGE_MESSAGE, content: null }] },
    ],
    [
      "a message whose content is a number",
      { messages: [{ ...IMAGE_MESSAGE, content: 42 }] },
    ],
    [
      "a content part that is null",
      { messages: [{ ...IMAGE_MESSAGE, content: [null] }] },
    ],
    [
      "an image part without its image",
      { messages: [{ ...IMAGE_MESSAGE, content: [{ type: "image_url" }] }] },
    ],
    [
      "a video part without its video",
      { messages: [{ ...IMAGE_MESSAGE, content: [{ type: "video_url" }] }] },
    ],
    [
      "an audio part without its audio",
      { messages: [{ ...IMAGE_MESSAGE, content: [{ type: "audio_url" }] }] },
    ],
  ])("drops a prompt with %s and keeps the others", async (_, fields) => {
    seed({
      promptIds: ["broken", "p1"],
      promptMap: {
        broken: storedPrompt("broken", fields),
        p1: storedPrompt("p1"),
      },
    });

    const { promptIds, promptMap } = await loadState();

    expect(promptIds).toEqual(["p1"]);
    expect(promptMap).toEqual({ p1: storedPrompt("p1") });
  });

  it("drops the outputs of the prompts it drops", async () => {
    const output = { isLoading: false, value: "Hi!", stale: false };
    seed({
      promptIds: ["p1", "missing"],
      promptMap: { p1: storedPrompt("p1") },
      outputMap: { p1: output, missing: output, unlisted: output },
    });

    const { outputMap } = await loadState();

    expect(outputMap).toEqual({ p1: output });
  });

  it("gives a prompt saved without a config or a provider an empty config", async () => {
    seed({
      promptIds: ["p1"],
      promptMap: { p1: storedPrompt("p1", { configs: undefined }) },
    });

    const { promptMap } = await loadState();

    expect(promptMap.p1).toEqual(storedPrompt("p1", { configs: {} }));
  });
});
