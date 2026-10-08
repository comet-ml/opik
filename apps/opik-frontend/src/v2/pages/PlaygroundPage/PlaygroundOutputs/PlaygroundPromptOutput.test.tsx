import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import PlaygroundPromptOutput from "./PlaygroundPromptOutput";

const PROMPT_ID = "prompt-1";
const STALE_NOTE =
  "Prompt changed since the last run. Re-run to update results.";

type Output = {
  isLoading: boolean;
  value: string | null;
  error?: string;
  stale: boolean;
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

  it("should render successful run output", () => {
    output = { isLoading: false, value: "the answer", stale: false };

    renderOutput();

    expect(screen.getByTestId("markdown")).toHaveTextContent("the answer");
    expect(
      screen.queryByTestId("playground-output-error"),
    ).not.toBeInTheDocument();
    expect(queryStaleNote()).not.toBeInTheDocument();
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
});
