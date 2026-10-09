import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import PromptImprovementDialog from "./PromptImprovementDialog";
import { PROVIDER_TYPE } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const OUTPUT_LIMIT_MESSAGE =
  "The model reached its output limit before finishing the prompt. Try a shorter instruction, or pick another model.";
const PARTIAL = "You are a poet. Write a haiku about";

const improvement = vi.hoisted(() => ({
  improvePrompt: vi.fn(),
}));

vi.mock("@uiw/react-codemirror", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  default: ({ value, editable }: { value: string; editable: boolean }) => (
    <textarea
      aria-label="Generated prompt"
      value={value}
      readOnly={!editable}
      onChange={() => {}}
    />
  ),
}));

vi.mock("@/hooks/usePromptImprovement", () => ({
  default: () => ({
    improvePrompt: improvement.improvePrompt,
    generatePrompt: vi.fn(),
  }),
}));

vi.mock("@/hooks/useModelSelection", () => ({
  default: () => ({
    model: "gpt-4o-mini",
    provider: "openai",
    configs: {},
    modelSelectProps: {},
  }),
}));

vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: () => null,
}));

const alertWith = (message: string) =>
  screen.getByText(message).closest('[role="alert"]');

type StreamResult = {
  result: string;
  finishReason?: string;
  providerError?: string;
};

const renderDialog = (streamed: StreamResult) => {
  improvement.improvePrompt.mockImplementation(
    async (
      _prompt: string,
      _instructions: string,
      _model: string,
      _configs: unknown,
      onChunk: (chunk: string) => void,
    ) => {
      if (streamed.result) onChunk(streamed.result);
      return streamed;
    },
  );
  const onAccept = vi.fn();
  render(
    <TooltipProvider>
      <PromptImprovementDialog
        open
        setOpen={vi.fn()}
        id="message-1"
        originalPrompt="write a haiku"
        model="gpt-4o-mini"
        provider={PROVIDER_TYPE.OPEN_AI}
        workspaceName="default"
        onAccept={onAccept}
      />
    </TooltipProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: /Improve prompt/ }));
  return { onAccept };
};

describe("PromptImprovementDialog when the model hits its output limit", () => {
  beforeEach(() => {
    improvement.improvePrompt.mockReset();
  });

  it("keeps the cut-off prompt editable and usable, with the message as a warning", async () => {
    const { onAccept } = renderDialog({
      result: PARTIAL,
      finishReason: "length",
    });

    const editor = await screen.findByRole("textbox", {
      name: "Generated prompt",
    });
    await waitFor(() => expect(editor).not.toHaveAttribute("readonly"));
    expect(editor).toHaveValue(PARTIAL);
    expect(alertWith(OUTPUT_LIMIT_MESSAGE)).not.toHaveClass("text-destructive");

    fireEvent.click(screen.getByRole("button", { name: "Use this prompt" }));

    expect(onAccept).toHaveBeenCalledWith("message-1", PARTIAL);
  });

  it.each<[string, StreamResult, string]>([
    [
      "a cut-off with no text",
      { result: "", finishReason: "length" },
      OUTPUT_LIMIT_MESSAGE,
    ],
    [
      "an answer with no text",
      { result: "  ", finishReason: "stop" },
      "The model did not return any content. Please try again or adjust your instructions.",
    ],
    [
      "a failed request",
      { result: PARTIAL, providerError: "Rate limit reached" },
      "Rate limit reached",
    ],
  ])("still blocks on %s", async (_, streamed, message) => {
    renderDialog(streamed);

    await waitFor(() => expect(screen.getByText(message)).toBeInTheDocument());
    expect(alertWith(message)).toHaveClass("text-destructive");
    expect(
      screen.queryByRole("textbox", { name: "Generated prompt" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Use this prompt" }),
    ).toBeDisabled();
  });
});
