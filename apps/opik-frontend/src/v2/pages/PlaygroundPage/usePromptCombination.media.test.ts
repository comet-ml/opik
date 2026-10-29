import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, MessageContent } from "@/types/llm";
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
vi.mock("@/v2/pages/PlaygroundPage/useHydratePromptMetadata", () => ({
  useHydratePromptMetadata: () => async () => undefined,
}));

const okRun: RunStreamingReturn = {
  result: "ok",
  startTime: "",
  endTime: "",
  usage: null,
  choices: null,
  finishReason: null,
  providerError: null,
  opikError: null,
  pythonProxyError: null,
  errorStatus: null,
  actualModel: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  actualProvider: PROVIDER_TYPE.OPEN_AI,
};

const promptWithContent = (content: MessageContent): PlaygroundPromptType => ({
  id: PROMPT_ID,
  name: "Prompt",
  model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  messages: [{ id: "message-1", role: LLM_MESSAGE_ROLE.user, content }],
});

const runWith = async (content: MessageContent) => {
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
    { prompt: promptWithContent(content) },
    { log: vi.fn(), finishLogging: vi.fn() },
  );

  return result;
};

const sentContent = () =>
  mocks.runStreaming.mock.calls[0][0].messages[0].content;

// A run without a dataset is the only one this hook still serves: dataset runs are built and sent
// by the server, which renders their media parts and expands list variables (OPIK-8831) with the
// dataset item in hand. What remains testable here is that a free-form run's media still reaches
// the provider as media rather than as text.
describe("usePromptCombination with media parts and no dataset", () => {
  beforeEach(() => {
    mocks.runStreaming.mockReset();
    mocks.runStreaming.mockResolvedValue(okRun);
  });

  it("sends image, video and audio parts as media, keeping the text around them", async () => {
    await runWith([
      { type: "text", text: "Describe these" },
      { type: "image_url", image_url: { url: "https://example.com/a.png" } },
      { type: "video_url", video_url: { url: "https://example.com/a.mp4" } },
      { type: "audio_url", audio_url: { url: "https://example.com/a.mp3" } },
    ] as MessageContent);

    expect(mocks.runStreaming).toHaveBeenCalledTimes(1);
    expect(sentContent()).toEqual([
      { type: "text", text: "Describe these" },
      { type: "image_url", image_url: { url: "https://example.com/a.png" } },
      { type: "video_url", video_url: { url: "https://example.com/a.mp4" } },
      { type: "audio_url", audio_url: { url: "https://example.com/a.mp3" } },
    ]);
  });

  // The guard reads variables out of the text, so a text one is refused before any request...
  it("refuses a prompt whose text holds a variable", async () => {
    await runWith([
      { type: "text", text: "Describe {{subject}}" },
      { type: "image_url", image_url: { url: "https://example.com/a.png" } },
    ] as MessageContent);

    expect(mocks.runStreaming).not.toHaveBeenCalled();
    expect(mocks.updateOutput).toHaveBeenLastCalledWith(
      PROMPT_ID,
      expect.objectContaining({ error: expect.stringContaining("subject") }),
    );
  });

  // ...and a media one is not, so it renders to an empty URL and the provider rejects it. Pinned
  // as it stands rather than as it should be: a variable in a media slot is only meaningful with a
  // dataset item to bind it, and that run is built on the server.
  it("sends an empty URL for a variable in a media slot", async () => {
    await runWith([
      { type: "image_url", image_url: { url: "{{images}}" } },
    ] as MessageContent);

    expect(sentContent()).toEqual([
      { type: "image_url", image_url: { url: "" } },
    ]);
  });
});
