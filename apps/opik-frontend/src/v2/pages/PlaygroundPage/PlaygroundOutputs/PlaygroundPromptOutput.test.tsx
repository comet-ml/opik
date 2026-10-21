import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { PlaygroundRunInputChange } from "@/types/playground";
import PlaygroundPromptOutput from "./PlaygroundPromptOutput";
import { RunFailureHint } from "@/lib/playground";

const PROMPT_ID = "prompt-1";
const STALE_NOTE =
  "Prompt changed since the last run. Re-run to update results.";

type Output = {
  isLoading: boolean;
  value: string | null;
  error?: string;
  errorHint?: RunFailureHint;
  stopped?: boolean;
  stale: boolean;
  staleChanges?: PlaygroundRunInputChange[];
  usage?: { duration?: number; totalTokens?: number };
};

let output: Output;

vi.mock("@/store/PlaygroundStore", () => ({
  useOutputByPromptDatasetItemId: () => output,
}));

vi.mock("@/v2/pages/PlaygroundPage/usePromptModelDisplay", () => ({
  default: () => ({ ProviderIcon: () => null, modelLabel: "" }),
}));

vi.mock("@/shared/MarkdownPreview/MarkdownPreview", () => ({
  default: ({
    children,
    className,
  }: {
    children: string | null;
    className?: string;
  }) => (
    <div data-testid="markdown" className={className}>
      {children}
    </div>
  ),
}));

const outputView = () => (
  <TooltipProvider>
    <PlaygroundPromptOutput promptId={PROMPT_ID} promptIndex={0} />
  </TooltipProvider>
);

const renderOutput = () => render(outputView());

const queryStaleNote = () =>
  screen.queryByTestId("playground-stale-output-note");

beforeEach(() => {
  output = { isLoading: false, value: null, stale: false };
});

describe("PlaygroundPromptOutput", () => {
  it("should show a failed run as an error rather than as the model's answer", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: false,
    };

    renderOutput();

    expect(screen.getByTestId("playground-output-error")).toHaveTextContent(
      "Run failed: ratings not defined",
    );
    expect(screen.queryByTestId("markdown")).not.toBeInTheDocument();
  });

  it("should name the failure on the tag and keep the next step apart from it", () => {
    const action =
      "Wait a moment and run again. If it keeps happening, lower Max concurrent requests or raise Throttling in Model parameters.";
    output = {
      isLoading: false,
      value: null,
      error: "Rate limit reached for gpt-4o on requests per min (RPM)",
      errorHint: { title: "Rate limit reached", action },
      stale: false,
    };

    renderOutput();

    const tag = screen.getByTestId("playground-output-error");
    expect(tag).toHaveTextContent("Run failed: Rate limit reached");
    expect(tag).not.toHaveTextContent("Wait a moment");

    const nextStep = screen.getByTestId("playground-output-error-action");
    expect(nextStep).toHaveTextContent(action);
    expect(tag).not.toContainElement(nextStep);
  });

  it("should keep the provider's error on the tag when the next step is only a suggestion", () => {
    const action =
      "This model needs a User message. Add one with + Message, then run again.";
    output = {
      isLoading: false,
      value: null,
      error: "messages: Field required",
      errorHint: { action },
      stale: false,
    };

    renderOutput();

    expect(screen.getByTestId("playground-output-error")).toHaveTextContent(
      "Run failed: messages: Field required",
    );
    expect(
      screen.getByTestId("playground-output-error-action"),
    ).toHaveTextContent(action);
  });

  it("should show no next step for an error that has none", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: false,
    };

    renderOutput();

    expect(
      screen.queryByTestId("playground-output-error-action"),
    ).not.toBeInTheDocument();
  });

  it("should render successful run output", () => {
    output = { isLoading: false, value: "the answer", stale: false };

    renderOutput();

    expect(screen.getByTestId("markdown")).toHaveTextContent("the answer");
    expect(
      screen.queryByTestId("playground-output-error"),
    ).not.toBeInTheDocument();
    expect(queryStaleNote()).not.toBeInTheDocument();
  });

  it("should keep the provider's error on the tag and show the next step under it", () => {
    const action =
      "This model isn't available for your API key. Pick another model, or check which models your provider account can use.";
    output = {
      isLoading: false,
      value: null,
      error: "model: claude-mythos-5",
      errorHint: { action },
      stale: false,
    };

    renderOutput();

    const tag = screen.getByTestId("playground-output-error");
    expect(tag).toHaveTextContent("Run failed: model: claude-mythos-5");

    const nextStep = screen.getByTestId("playground-output-error-action");
    expect(nextStep).toHaveTextContent(action);
    expect(tag).not.toContainElement(nextStep);
  });

  // Editing the prompt marks the previous output stale. The reason is most wanted
  // exactly then — while correcting the prompt — so it dims rather than vanishing,
  // as output and chips already do.
  it("should dim a stale error instead of hiding it, and say the prompt changed", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: true,
    };

    renderOutput();

    const tag = screen.getByTestId("playground-output-error");
    expect(tag).toHaveTextContent("Run failed: ratings not defined");
    expect(tag).toHaveClass("opacity-50");
    expect(queryStaleNote()).toHaveTextContent(STALE_NOTE);
  });

  it("should not dim the error of the current run", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: false,
    };

    renderOutput();

    expect(screen.getByTestId("playground-output-error")).not.toHaveClass(
      "opacity-50",
    );
    expect(queryStaleNote()).not.toBeInTheDocument();
  });

  it("should keep stale output from a run that succeeded, dimmed, and say the prompt changed", () => {
    output = { isLoading: false, value: "the answer", stale: true };

    renderOutput();

    const markdown = screen.getByTestId("markdown");
    expect(markdown).toHaveTextContent("the answer");
    expect(markdown).toHaveClass("text-muted-gray");
    expect(screen.getByText("Output A")).toHaveClass("text-muted-gray");
    expect(queryStaleNote()).toHaveTextContent(STALE_NOTE);
    expect(screen.queryByText("No runs yet")).not.toBeInTheDocument();
  });

  it("should say what changed since the last run", () => {
    output = {
      isLoading: false,
      value: "the answer",
      stale: true,
      staleChanges: ["parameters", "prompt"],
    };

    renderOutput();

    expect(queryStaleNote()).toHaveTextContent(
      "Prompt and parameters changed since the last run. Re-run to update results.",
    );
  });

  it("should drop the note as soon as the prompt is run again", () => {
    output = { isLoading: false, value: "the old answer", stale: true };
    const { rerender } = renderOutput();
    expect(queryStaleNote()).toBeInTheDocument();

    output = { isLoading: true, value: null, stale: false };
    rerender(outputView());
    expect(queryStaleNote()).not.toBeInTheDocument();

    output = { isLoading: false, value: "the new answer", stale: false };
    rerender(outputView());
    const markdown = screen.getByTestId("markdown");
    expect(markdown).toHaveTextContent("the new answer");
    expect(markdown).not.toHaveClass("text-muted-gray");
    expect(screen.getByText("Output A")).not.toHaveClass("text-muted-gray");
    expect(queryStaleNote()).not.toBeInTheDocument();
  });

  describe("a run the user stopped", () => {
    const queryStoppedTag = () =>
      screen.queryByTestId("playground-output-stopped");

    it("should keep the partial answer and say it was stopped, with no token count", () => {
      output = {
        isLoading: false,
        value: "Once upon a",
        stopped: true,
        stale: false,
        usage: { duration: 2.4 },
      };

      renderOutput();

      expect(screen.getByTestId("markdown")).toHaveTextContent("Once upon a");
      expect(queryStoppedTag()).toHaveTextContent("Stopped");
      expect(screen.getByText("2.4s")).toBeInTheDocument();
      expect(screen.getByText("n/a tokens")).toBeInTheDocument();
      expect(
        screen.queryByTestId("playground-output-error"),
      ).not.toBeInTheDocument();
    });

    it("should say it was stopped rather than No runs yet when no text came back", () => {
      output = {
        isLoading: false,
        value: null,
        stopped: true,
        stale: false,
        usage: { duration: 0.8 },
      };

      renderOutput();

      expect(queryStoppedTag()).toBeInTheDocument();
      expect(screen.getByText("n/a tokens")).toBeInTheDocument();
      expect(screen.queryByText("No runs yet")).not.toBeInTheDocument();
    });

    it("should drop the label and n/a once the prompt runs to the end", () => {
      output = {
        isLoading: false,
        value: "Once upon a",
        stopped: true,
        stale: false,
        usage: { duration: 2.4 },
      };
      const { rerender } = renderOutput();
      expect(queryStoppedTag()).toBeInTheDocument();

      output = {
        isLoading: false,
        value: "Once upon a time",
        stopped: false,
        stale: false,
        usage: { duration: 1.2, totalTokens: 42 },
      };
      rerender(outputView());

      expect(screen.getByText("42 tokens")).toBeInTheDocument();
      expect(queryStoppedTag()).not.toBeInTheDocument();
      expect(screen.queryByText("n/a tokens")).not.toBeInTheDocument();
    });
  });
});
