import { beforeEach, describe, expect, it } from "vitest";

import usePlaygroundStore from "@/store/PlaygroundStore";
import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

const createPrompt = (id: string): PlaygroundPromptType => ({
  id,
  name: "Prompt",
  messages: [],
  model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
});

const promptIds = () => usePlaygroundStore.getState().promptIds;

describe("addPrompt", () => {
  beforeEach(() => {
    usePlaygroundStore
      .getState()
      .setPromptMap(["a", "b"], { a: createPrompt("a"), b: createPrompt("b") });
  });

  it("inserts the prompt at the given position", () => {
    usePlaygroundStore.getState().addPrompt(createPrompt("copy-of-a"), 1);

    expect(promptIds()).toEqual(["a", "copy-of-a", "b"]);
    expect(usePlaygroundStore.getState().promptMap["copy-of-a"]).toBeDefined();
  });

  it("appends the prompt when no position is given", () => {
    usePlaygroundStore.getState().addPrompt(createPrompt("c"));

    expect(promptIds()).toEqual(["a", "b", "c"]);
  });
});
