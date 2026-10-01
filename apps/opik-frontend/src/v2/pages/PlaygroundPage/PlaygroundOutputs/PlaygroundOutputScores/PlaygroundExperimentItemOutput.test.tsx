import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import PlaygroundExperimentItemOutput from "./PlaygroundExperimentItemOutput";
import { PlaygroundExperimentItem } from "@/v2/pages/PlaygroundPage/PlaygroundOutputs/usePlaygroundExperimentItem";

vi.mock("@/shared/MarkdownPreview/MarkdownPreview", () => ({
  default: ({ children }: { children: string | null }) => (
    <div data-testid="markdown">{children}</div>
  ),
}));

const buildItem = (
  overrides: Partial<PlaygroundExperimentItem> = {},
): PlaygroundExperimentItem => ({
  hasItem: true,
  output: "the answer",
  error: null,
  traceId: "trace-1",
  runCount: 1,
  ...overrides,
});

describe("PlaygroundExperimentItemOutput", () => {
  it("renders the output the run produced", () => {
    render(<PlaygroundExperimentItemOutput item={buildItem()} />);

    expect(screen.getByTestId("markdown")).toHaveTextContent("the answer");
  });

  it("renders nothing when the call came back without content", () => {
    const { container } = render(
      <PlaygroundExperimentItemOutput item={buildItem({ output: null })} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("says which run the output belongs to once a row has been run more than once", () => {
    render(
      <PlaygroundExperimentItemOutput item={buildItem({ runCount: 2 })} />,
    );

    expect(screen.getByText("Output (last run):")).toBeInTheDocument();
  });

  it("stays quiet about run count on a single run", () => {
    render(<PlaygroundExperimentItemOutput item={buildItem()} />);

    expect(screen.queryByText("Output (last run):")).not.toBeInTheDocument();
  });
});
