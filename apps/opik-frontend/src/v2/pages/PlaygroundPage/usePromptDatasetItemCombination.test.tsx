import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { DatasetItem } from "@/types/datasets";
import { LogProcessor } from "@/api/playground/createLogPlaygroundProcessor";
import usePlaygroundStore from "@/store/PlaygroundStore";

const get = vi.fn();

vi.mock("@/api/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/api")>()),
  default: { get: (...args: unknown[]) => get(...args) },
}));

vi.mock("@/api/playground/useCompletionProxyStreaming", () => ({
  default: () => async () => ({
    result: "Hello",
    startTime: "2026-10-07T10:00:00.000Z",
    endTime: "2026-10-07T10:00:01.000Z",
  }),
}));

import usePromptDatasetItemCombination from "./usePromptDatasetItemCombination";

const TEMPLATE = JSON.stringify([{ role: "user", content: "Hi" }]);

const playgroundPrompt = (
  overrides: Partial<PlaygroundPromptType> = {},
): PlaygroundPromptType => ({
  name: "Prompt A",
  id: "playground-prompt-a",
  messages: [{ id: "m1", role: LLM_MESSAGE_ROLE.user, content: "Hi" }],
  model: PROVIDER_MODEL_TYPE.GPT_4O,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  ...overrides,
});

const datasetItem = { id: "item-1", data: {} } as DatasetItem;

const runAndCaptureLog = async (prompt: PlaygroundPromptType) => {
  usePlaygroundStore.setState({ isRunningMap: { [prompt.id]: true } });

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(
    () =>
      usePromptDatasetItemCombination({
        datasetItems: [datasetItem],
        workspaceName: "default",
        datasetName: "dataset",
        selectedRuleIds: null,
        addAbortController: vi.fn(),
        deleteAbortController: vi.fn(),
        throttlingSeconds: 0,
      }),
    { wrapper },
  );

  const logProcessor: LogProcessor = { log: vi.fn(), finishLogging: vi.fn() };
  await act(async () => {
    await result.current.processCombination(
      { datasetItem, prompt },
      logProcessor,
    );
  });

  return vi.mocked(logProcessor.log).mock.calls[0][0];
};

describe("usePromptDatasetItemCombination prompt version links", () => {
  beforeEach(() => {
    get.mockReset();
    get.mockImplementation(async (url: string) =>
      url.includes("/versions/")
        ? { data: { id: "picked-version", template: TEMPLATE } }
        : {
            data: {
              id: "library-prompt",
              name: "Library prompt",
              latest_version: { id: "latest-version", template: TEMPLATE },
            },
          },
    );
  });

  it("links the latest version when the library prompt was loaded as latest", async () => {
    const run = await runAndCaptureLog(
      playgroundPrompt({ loadedChatPromptId: "library-prompt" }),
    );

    expect(run.promptLibraryVersions).toEqual([{ id: "latest-version" }]);
    expect(run.promptLibraryMetadata?.version.id).toBe("latest-version");
  });

  it("links the explicitly picked version", async () => {
    const run = await runAndCaptureLog(
      playgroundPrompt({
        loadedChatPromptId: "library-prompt",
        loadedChatPromptVersionId: "picked-version",
      }),
    );

    expect(run.promptLibraryVersions).toEqual([{ id: "picked-version" }]);
    expect(run.promptLibraryMetadata?.version.id).toBe("picked-version");
  });
});
