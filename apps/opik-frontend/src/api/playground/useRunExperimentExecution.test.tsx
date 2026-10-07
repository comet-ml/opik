import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";

const get = vi.fn();
const post = vi.fn();

vi.mock("@/api/api", () => ({
  default: {
    get: (...args: unknown[]) => get(...args),
    post: (...args: unknown[]) => post(...args),
  },
  PROMPTS_REST_ENDPOINT: "/v1/private/prompts/",
  EXPERIMENT_EXECUTION_REST_ENDPOINT: "/v1/private/experiments/execute",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

import useRunExperimentExecution from "./useRunExperimentExecution";

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

const runAndCaptureRequest = async (prompt: PlaygroundPromptType) => {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const { result } = renderHook(() => useRunExperimentExecution(), {
    wrapper,
  });

  await act(async () => {
    await result.current.mutateAsync({
      datasetName: "suite",
      datasetId: "suite-id",
      prompts: [prompt],
    });
  });

  return post.mock.calls[0][1];
};

describe("useRunExperimentExecution prompt version links", () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    get.mockResolvedValue({
      data: { id: "library-prompt", latest_version: { id: "latest-version" } },
    });
    post.mockResolvedValue({ data: { experiments: [], total_items: 0 } });
  });

  it("sends the latest version when the library prompt was loaded as latest", async () => {
    const request = await runAndCaptureRequest(
      playgroundPrompt({ loadedChatPromptId: "library-prompt" }),
    );

    expect(get).toHaveBeenCalledWith(
      "/v1/private/prompts/library-prompt",
      expect.anything(),
    );
    expect(request.prompts[0].prompt_versions).toEqual([
      { id: "latest-version", prompt_id: "library-prompt" },
    ]);
  });

  it("sends the explicitly picked version", async () => {
    const request = await runAndCaptureRequest(
      playgroundPrompt({
        loadedChatPromptId: "library-prompt",
        loadedChatPromptVersionId: "picked-version",
      }),
    );

    expect(get).not.toHaveBeenCalled();
    expect(request.prompts[0].prompt_versions).toEqual([
      { id: "picked-version", prompt_id: "library-prompt" },
    ]);
  });
});
