import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import PlaygroundPromptOutput from "./PlaygroundPromptOutput";

const PROMPT_ID = "prompt-1";

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
  default: ({ children }: { children: string | null }) => (
    <div data-testid="markdown">{children}</div>
  ),
}));

const renderOutput = () =>
  render(
    <TooltipProvider>
      <PlaygroundPromptOutput promptId={PROMPT_ID} promptIndex={0} />
    </TooltipProvider>,
  );

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

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Run failed: ratings not defined",
    );
    expect(screen.queryByTestId("markdown")).not.toBeInTheDocument();
  });

  it("should render a successful run unchanged", () => {
    output = { isLoading: false, value: "the answer", stale: false };

    renderOutput();

    expect(screen.getByTestId("markdown")).toHaveTextContent("the answer");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // Editing the prompt marks the previous output stale. The reason is most wanted
  // exactly then — while correcting the prompt — so it dims rather than vanishing,
  // as output and chips already do.
  it("should dim a stale error instead of hiding it", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: true,
    };

    renderOutput();

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Run failed: ratings not defined");
    expect(alert).toHaveClass("opacity-50");
  });

  it("should not dim the error of the current run", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: false,
    };

    renderOutput();

    expect(screen.getByRole("alert")).not.toHaveClass("opacity-50");
  });

  it("should keep showing stale output from a run that succeeded", () => {
    output = { isLoading: false, value: "the answer", stale: true };

    renderOutput();

    expect(screen.getByTestId("markdown")).toHaveTextContent("the answer");
  });
});
