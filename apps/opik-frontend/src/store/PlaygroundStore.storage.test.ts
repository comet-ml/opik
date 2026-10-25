import { afterEach, describe, expect, it, vi } from "vitest";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PlaygroundPromptType } from "@/types/playground";

const PROMPT: PlaygroundPromptType = {
  id: "p1",
  name: "Prompt",
  model: "",
  provider: "",
  configs: {},
  messages: [{ id: "m1", role: LLM_MESSAGE_ROLE.user, content: "Say hi" }],
};

// Like a browser whose storage quota is nearly used up: values longer than maxLength don't fit.
const stubLocalStorage = (maxLength: number) => {
  const items = new Map<string, string>();

  vi.stubGlobal("localStorage", {
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => {
      if (value.length > maxLength) {
        throw new DOMException(
          "The quota has been exceeded.",
          "QuotaExceededError",
        );
      }
      items.set(key, value);
    },
    removeItem: (key: string) => items.delete(key),
  });

  return () => JSON.parse(items.get("PLAYGROUND_STATE")!).state;
};

// The store is a module singleton that picks its storage on import, so each case needs a fresh module.
const loadStore = async () => {
  vi.resetModules();
  const { default: usePlaygroundStore } = await import(
    "@/store/PlaygroundStore"
  );
  return usePlaygroundStore;
};

describe("PLAYGROUND_STATE saving", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("keeps an update in memory without throwing when the storage is full", async () => {
    stubLocalStorage(0);
    vi.spyOn(console, "warn").mockImplementation(() => {});
    const store = await loadStore();

    expect(() =>
      store.getState().setPromptMap(["p1"], { p1: PROMPT }),
    ).not.toThrow();
    expect(store.getState().promptIds).toEqual(["p1"]);
  });

  it("saves the outputs while they fit, and only the prompts once they don't", async () => {
    const readSaved = stubLocalStorage(5000);
    vi.spyOn(console, "warn").mockImplementation(() => {});
    const store = await loadStore();
    const { setPromptMap, updateOutput, updatePrompt } = store.getState();

    setPromptMap(["p1"], { p1: PROMPT });
    updateOutput("p1", { isLoading: false, value: "Hi!" });

    expect(readSaved().outputMap.p1.value).toBe("Hi!");

    const longOutput = "Hi! ".repeat(2000);
    updateOutput("p1", { value: longOutput });
    updatePrompt("p1", { name: "Renamed" });

    expect(readSaved().promptMap.p1.name).toBe("Renamed");
    expect(readSaved().outputMap).toEqual({});
    expect(store.getState().outputMap.p1).toMatchObject({
      value: longOutput,
    });
  });

  it("warns once, not on every update that can't be saved", async () => {
    stubLocalStorage(0);
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const store = await loadStore();
    const { setDatasetPage } = store.getState();

    setDatasetPage(2);
    setDatasetPage(3);
    setDatasetPage(4);

    expect(warn).toHaveBeenCalledTimes(1);
  });
});
