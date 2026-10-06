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
  });

  it("should show No runs yet instead of stale output once the prompt is edited", () => {
    output = { isLoading: false, value: "the answer", stale: true };

    renderOutput();

    expect(screen.getByText("No runs yet")).toBeInTheDocument();
    expect(screen.queryByTestId("markdown")).not.toBeInTheDocument();
  });

  it("should show No runs yet instead of a stale error once the prompt is edited", () => {
    output = {
      isLoading: false,
      value: null,
      error: "ratings not defined",
      stale: true,
    };

    renderOutput();

    expect(screen.getByText("No runs yet")).toBeInTheDocument();
    expect(
      screen.queryByTestId("playground-output-error"),
    ).not.toBeInTheDocument();
  });
});
