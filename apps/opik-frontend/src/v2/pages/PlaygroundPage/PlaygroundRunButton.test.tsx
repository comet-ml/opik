import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, MessageContent } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { DATASET_TYPE } from "@/types/datasets";
import PlaygroundRunButton from "./PlaygroundRunButton";

const MEDIA_REASON =
  "This prompt contains media but the selected model doesn't support media input";

let prompt: PlaygroundPromptType | undefined;
let datasetType: DATASET_TYPE | null = null;
let playgroundDataset: { datasetId: string | null; itemsTotal?: number } = {
  datasetId: null,
};

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptById: () => prompt,
  useIsPromptRunning: () => false,
  useDatasetType: () => datasetType,
}));

vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => playgroundDataset,
}));

const createPrompt = (
  model: PROVIDER_MODEL_TYPE,
  content: MessageContent,
): PlaygroundPromptType => ({
  id: "prompt-1",
  name: "Prompt",
  model,
  provider: PROVIDER_TYPE.OPEN_AI,
  configs: {},
  messages: [{ id: "message-1", role: LLM_MESSAGE_ROLE.user, content }],
});

const imageContent: MessageContent = [
  { type: "text", text: "Describe this" },
  { type: "image_url", image_url: { url: "https://example.com/cat.png" } },
];

const videoContent: MessageContent = [
  { type: "text", text: "Describe this" },
  { type: "video_url", video_url: { url: "https://example.com/cat.mp4" } },
];

const onRun = vi.fn();

const renderButton = () =>
  render(
    <TooltipProvider delayDuration={0}>
      <PlaygroundRunButton promptId="prompt-1" onRun={onRun} onStop={vi.fn()} />
    </TooltipProvider>,
  );

const getRunButton = () => screen.getByRole("button", { name: "Run" });

// Radix opens tooltips on pointer move, not on focus, for a disabled trigger:
// a disabled button cannot take focus, but React still delivers pointer events.
const hoverRunButton = () =>
  fireEvent.pointerMove(getRunButton(), { pointerType: "mouse" });

beforeEach(() => {
  onRun.mockClear();
  datasetType = null;
  playgroundDataset = { datasetId: null };
});

describe("PlaygroundRunButton", () => {
  describe("media the model cannot read", () => {
    it("should disable Run and explain why for an image on a non-vision model", async () => {
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_4, imageContent);

      renderButton();
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        MEDIA_REASON,
      );
    });

    it("should disable Run for a video on a non-vision model", async () => {
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_4, videoContent);

      renderButton();
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        MEDIA_REASON,
      );
    });

    it("should not call onRun when the disabled button is clicked", () => {
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_4, imageContent);

      renderButton();
      fireEvent.click(getRunButton());

      expect(onRun).not.toHaveBeenCalled();
    });
  });

  describe("prompts the model can run", () => {
    it("should enable Run for an image on a vision model", () => {
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_5_5, imageContent);

      renderButton();
      fireEvent.click(getRunButton());

      expect(getRunButton()).toBeEnabled();
      expect(onRun).toHaveBeenCalledTimes(1);
    });

    it("should enable Run for text only on a non-vision model", () => {
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_4, "Say hello");

      renderButton();

      expect(getRunButton()).toBeEnabled();
    });
  });

  describe("a dataset version with no items", () => {
    beforeEach(() => {
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_4, "Say hello");
      datasetType = DATASET_TYPE.DATASET;
      playgroundDataset = { datasetId: "dataset-1::version-1", itemsTotal: 0 };
    });

    it("should disable Run and explain why", async () => {
      renderButton();
      hoverRunButton();

      expect(getRunButton()).toBeDisabled();
      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "This dataset is empty. Add items to run an experiment",
      );
    });

    it("should name a test suite in the reason", async () => {
      datasetType = DATASET_TYPE.TEST_SUITE;

      renderButton();
      hoverRunButton();

      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "This test suite is empty. Add items to run an experiment",
      );
    });

    it("should not call onRun when the disabled button is clicked", () => {
      renderButton();
      fireEvent.click(getRunButton());

      expect(onRun).not.toHaveBeenCalled();
    });

    it("should keep Run enabled while the item count is not known yet", () => {
      playgroundDataset = { datasetId: "dataset-1::version-1" };

      renderButton();

      expect(getRunButton()).toBeEnabled();
    });
  });

  describe("reason priority", () => {
    it("should report an empty message before the media problem", async () => {
      prompt = {
        ...createPrompt(PROVIDER_MODEL_TYPE.GPT_4, imageContent),
        messages: [
          {
            id: "message-1",
            role: LLM_MESSAGE_ROLE.user,
            content: imageContent,
          },
          { id: "message-2", role: LLM_MESSAGE_ROLE.user, content: "" },
        ],
      };

      renderButton();
      hoverRunButton();

      expect(await screen.findByRole("tooltip")).toHaveTextContent(
        "Message is empty. Please add some text to proceed",
      );
    });
  });
});
