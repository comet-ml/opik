import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { PlaygroundPromptType } from "@/types/playground";
import { LLM_MESSAGE_ROLE, MessageContent } from "@/types/llm";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import PlaygroundRunButton from "./PlaygroundRunButton";

const MEDIA_REASON =
  "This prompt contains media but the selected model doesn't support media input";

let prompt: PlaygroundPromptType | undefined;

let isResumingRun = false;

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptById: () => prompt,
  useIsPromptRunning: () => false,
  useIsResumingRun: () => isResumingRun,
  useDatasetItemsTotal: () => null,
}));

// The button also refuses a run whose dataset has nothing left to run; these cases are about the
// media check, so the dataset is left out of the way.
vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: undefined }),
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
  isResumingRun = false;
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

  // A reopened page does not yet know whether its run is still going. Starting one here in that
  // window would run alongside it, and the resume then drops the result.
  describe("while the page is picking a run back up", () => {
    beforeEach(() => {
      isResumingRun = true;
      prompt = createPrompt(PROVIDER_MODEL_TYPE.GPT_4, "Say hello");
    });

    it("should disable Run on a prompt that is otherwise runnable", () => {
      renderButton();

      expect(getRunButton()).toBeDisabled();
    });

    it("should not call onRun when clicked in that window", () => {
      renderButton();
      fireEvent.click(getRunButton());

      expect(onRun).not.toHaveBeenCalled();
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
