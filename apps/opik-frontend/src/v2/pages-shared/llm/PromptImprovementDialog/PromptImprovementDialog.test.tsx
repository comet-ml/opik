import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";

import PromptImprovementDialog from "./PromptImprovementDialog";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const OUTPUT_LIMIT_MESSAGE =
  "The model reached its output limit before finishing the prompt. Try a shorter instruction, or pick another model.";
const PARTIAL = "You are a poet. Write a haiku about";

const improvement = vi.hoisted(() => ({
  improvePrompt: vi.fn(),
  generatePrompt: vi.fn(),
}));

const selection = vi.hoisted(() => ({ lastPicked: "" }));

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
  default: () => improvement,
}));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [selection.lastPicked, vi.fn()],
}));

vi.mock("@/api/provider-keys/useProviderKeys", () => ({
  default: () => ({
    data: { content: [{ ui_composed_provider: PROVIDER_TYPE.OPEN_AI }] },
  }),
}));

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: (model: string) =>
      model.startsWith("claude")
        ? PROVIDER_TYPE.ANTHROPIC
        : model
          ? PROVIDER_TYPE.OPEN_AI
          : "",
    calculateDefaultModel: () => PROVIDER_MODEL_TYPE.GPT_4O_MINI,
    isDropdownModel: () => true,
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

beforeEach(() => {
  improvement.improvePrompt.mockReset();
  improvement.generatePrompt.mockReset();
  selection.lastPicked = "";
});

describe("PromptImprovementDialog — how a run's result is shown", () => {
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

const dialogProps = {
  setOpen: vi.fn(),
  id: "message-1",
  provider: PROVIDER_TYPE.OPEN_AI as COMPOSED_PROVIDER_TYPE,
  workspaceName: "default",
  onAccept: vi.fn(),
};

const renderOpen = (model: string, originalPrompt = "write a haiku") =>
  render(
    <TooltipProvider>
      <PromptImprovementDialog
        {...dialogProps}
        open
        model={model}
        originalPrompt={originalPrompt}
      />
    </TooltipProvider>,
  );

describe("PromptImprovementDialog — the model and settings a run sends", () => {
  it.each([
    {
      name: "the prompt's reasoning model, with its effort lowered",
      lastPicked: "",
      expectedModel: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      expectedEffort: "low",
    },
    {
      name: "the prompt's model when the saved pick has no provider key",
      lastPicked: PROVIDER_MODEL_TYPE.CLAUDE_HAIKU_4_5,
      expectedModel: PROVIDER_MODEL_TYPE.GPT_5_NANO,
      expectedEffort: "low",
    },
    {
      name: "the saved pick, at its defaults",
      lastPicked: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      expectedModel: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
      expectedEffort: undefined,
    },
  ])(
    "improves with $name",
    async ({ lastPicked, expectedModel, expectedEffort }) => {
      selection.lastPicked = lastPicked;
      improvement.improvePrompt.mockResolvedValue({ result: PARTIAL });
      renderOpen(PROVIDER_MODEL_TYPE.GPT_5_NANO);

      fireEvent.click(screen.getByRole("button", { name: /Improve prompt/ }));

      await waitFor(() => expect(improvement.improvePrompt).toHaveBeenCalled());
      const [, , model, configs] = improvement.improvePrompt.mock.calls[0];
      expect(model).toBe(expectedModel);
      expect(configs).toMatchObject({
        temperature: 0,
        maxCompletionTokens: 4000,
      });
      expect(configs.reasoningEffort).toBe(expectedEffort);
    },
  );

  it("generates with the same lowered settings", async () => {
    improvement.generatePrompt.mockResolvedValue({ result: PARTIAL });
    renderOpen(PROVIDER_MODEL_TYPE.GPT_5_NANO, "");

    fireEvent.change(
      screen.getByPlaceholderText("What do you want your AI to do?"),
      { target: { value: "a haiku writer" } },
    );
    fireEvent.click(screen.getByRole("button", { name: /Generate prompt/ }));

    await waitFor(() => expect(improvement.generatePrompt).toHaveBeenCalled());
    const [instructions, model, configs] =
      improvement.generatePrompt.mock.calls[0];
    expect(instructions).toBe("a haiku writer");
    expect(model).toBe(PROVIDER_MODEL_TYPE.GPT_5_NANO);
    expect(configs).toMatchObject({ reasoningEffort: "low" });
  });

  it.each([
    [PROVIDER_MODEL_TYPE.GPT_5_NANO, true],
    [PROVIDER_MODEL_TYPE.GPT_4O_MINI, false],
  ])("says when %s runs at low reasoning effort", (model, lowered) => {
    renderOpen(model);

    expect(screen.queryByText(/with low reasoning effort/) !== null).toBe(
      lowered,
    );
  });
});

describe("PromptImprovementDialog — closing during a run", () => {
  it("drops the old run's output after the dialog is closed and reopened", async () => {
    let streamOldChunk: (chunk: string) => void = () => {};
    let finishOldRun: (result: object) => void = () => {};
    let oldSignal: AbortSignal | undefined;
    improvement.improvePrompt.mockImplementation(
      (
        _prompt: string,
        _instructions: string,
        _model: string,
        _configs: unknown,
        onChunk: (chunk: string) => void,
        signal: AbortSignal,
      ) => {
        streamOldChunk = onChunk;
        oldSignal = signal;
        return new Promise((resolve) => {
          finishOldRun = resolve;
        });
      },
    );
    const dialogFor = (open: boolean) => (
      <TooltipProvider>
        <PromptImprovementDialog
          {...dialogProps}
          open={open}
          model={PROVIDER_MODEL_TYPE.GPT_4O_MINI}
          originalPrompt="write a haiku"
        />
      </TooltipProvider>
    );
    const { rerender } = render(dialogFor(true));
    fireEvent.click(screen.getByRole("button", { name: /Improve prompt/ }));
    await waitFor(() => expect(improvement.improvePrompt).toHaveBeenCalled());

    rerender(dialogFor(false));
    rerender(dialogFor(true));
    await act(async () => {
      streamOldChunk("Old prompt");
      finishOldRun({ result: "Old prompt", finishReason: "length" });
    });

    expect(oldSignal?.aborted).toBe(true);
    expect(
      screen.queryByRole("textbox", { name: "Generated prompt" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByText(OUTPUT_LIMIT_MESSAGE)).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Use this prompt" }),
    ).toBeDisabled();
    expect(
      screen.getByRole("button", { name: /Improve prompt/ }),
    ).toBeEnabled();
  });
});
