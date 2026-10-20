import { describe, expect, it, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";

import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";
import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, MessageContent } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import usePromptDatasetItemCombination from "./usePromptDatasetItemCombination";

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
  useHydrateDatasetItemData: () => async (item?: DatasetItem) =>
    item?.data ?? {},
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

const runWith = async (
  content: MessageContent,
  data: DatasetItem["data"],
): Promise<MessageContent> => {
  const { result } = renderHook(() =>
    usePromptDatasetItemCombination({
      datasetItems: [],
      workspaceName: "workspace",
      datasetName: "dataset",
      selectedRuleIds: null,
      addAbortController: vi.fn(),
      deleteAbortController: vi.fn(),
      throttlingSeconds: 0,
    }),
  );

  await result.current.processCombination(
    {
      prompt: promptWithContent(content),
      datasetItem: { id: "item-1", data } as DatasetItem,
    },
    { log: vi.fn(), finishLogging: vi.fn() },
  );

  expect(mocks.runStreaming).toHaveBeenCalledTimes(1);
  return mocks.runStreaming.mock.calls[0][0].messages[0].content;
};

const image = (url: string) => ({ type: "image_url", image_url: { url } });

describe("usePromptDatasetItemCombination with a list in a media slot", () => {
  beforeEach(() => {
    mocks.runStreaming.mockReset();
    mocks.runStreaming.mockResolvedValue(okRun);
  });

  it("sends one image per list item, in order, between the parts around it", async () => {
    const content = await runWith(
      [
        { type: "text", text: "Classify these {{count}} images" },
        { type: "image_url", image_url: { url: "{{images}}" } },
        { type: "text", text: "Answer as JSON" },
      ],
      {
        count: 3,
        images: [
          "https://example.com/1.png",
          "data:image/png;base64,AAAA",
          "https://example.com/3.png",
        ],
      },
    );

    expect(content).toEqual([
      { type: "text", text: "Classify these 3 images" },
      image("https://example.com/1.png"),
      image("data:image/png;base64,AAAA"),
      image("https://example.com/3.png"),
      { type: "text", text: "Answer as JSON" },
    ]);
  });

  it("expands a list that a CSV upload stored as JSON text", async () => {
    const content = await runWith(
      [{ type: "image_url", image_url: { url: "{{images}}" } }],
      { images: '["https://example.com/1.png","https://example.com/2.png"]' },
    );

    expect(content).toEqual([
      image("https://example.com/1.png"),
      image("https://example.com/2.png"),
    ]);
  });

  it("drops the slot for an empty list", async () => {
    const content = await runWith(
      [
        { type: "text", text: "Nothing to classify" },
        { type: "image_url", image_url: { url: "{{images}}" } },
      ],
      { images: [] },
    );

    expect(content).toEqual([{ type: "text", text: "Nothing to classify" }]);
  });

  it("still sends a list used in the text as JSON", async () => {
    const content = await runWith(
      [
        { type: "text", text: "Files: {{images}}" },
        { type: "image_url", image_url: { url: "{{images}}" } },
      ],
      { images: ["https://example.com/1.png", "https://example.com/2.png"] },
    );

    expect(content).toEqual([
      {
        type: "text",
        text: 'Files: ["https://example.com/1.png","https://example.com/2.png"]',
      },
      image("https://example.com/1.png"),
      image("https://example.com/2.png"),
    ]);
  });

  it("expands video and audio lists the same way", async () => {
    const content = await runWith(
      [
        { type: "video_url", video_url: { url: "{{videos}}" } },
        { type: "audio_url", audio_url: { url: "{{audios}}" } },
      ],
      {
        videos: ["https://example.com/1.mp4", "https://example.com/2.mp4"],
        audios: ["https://example.com/1.wav", "https://example.com/2.wav"],
      },
    );

    expect(content).toEqual([
      { type: "video_url", video_url: { url: "https://example.com/1.mp4" } },
      { type: "video_url", video_url: { url: "https://example.com/2.mp4" } },
      { type: "audio_url", audio_url: { url: "https://example.com/1.wav" } },
      { type: "audio_url", audio_url: { url: "https://example.com/2.wav" } },
    ]);
  });

  it("keeps fixed image slots and single values as they were", async () => {
    const content = await runWith(
      [
        { type: "image_url", image_url: { url: "{{image_1}}" } },
        { type: "image_url", image_url: { url: "{{image_2}}" } },
        { type: "image_url", image_url: { url: "https://cdn/{{name}}.png" } },
      ],
      {
        image_1: "https://example.com/1.png",
        image_2: "data:image/png;base64,BBBB",
        name: "cat",
      },
    );

    expect(content).toEqual([
      image("https://example.com/1.png"),
      image("data:image/png;base64,BBBB"),
      image("https://cdn/cat.png"),
    ]);
  });
});
