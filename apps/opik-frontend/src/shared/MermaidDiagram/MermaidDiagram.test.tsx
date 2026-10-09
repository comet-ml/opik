import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import mermaid from "mermaid";
import MermaidDiagram from "./MermaidDiagram";

vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    parse: vi.fn(),
    render: vi.fn(),
  },
}));

const VALID_PARSE = { diagramType: "flowchart", config: {} };

const FAILED_MESSAGE = "This graph can't be displayed";

describe("MermaidDiagram", () => {
  beforeEach(() => {
    vi.mocked(mermaid.parse).mockReset();
    vi.mocked(mermaid.render).mockReset();
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  it("should render the diagram when the chart is valid", async () => {
    vi.mocked(mermaid.parse).mockResolvedValue(VALID_PARSE);
    vi.mocked(mermaid.render).mockResolvedValue({
      svg: '<svg data-testid="diagram"></svg>',
      diagramType: "flowchart",
    });

    render(<MermaidDiagram chart="graph TD; a --> b" />);

    expect(await screen.findByTestId("diagram")).toBeInTheDocument();
    expect(screen.queryByText(FAILED_MESSAGE)).not.toBeInTheDocument();
  });

  it("should show a message and skip rendering when the chart can't be parsed", async () => {
    vi.mocked(mermaid.parse).mockResolvedValue(
      false as unknown as Awaited<ReturnType<typeof mermaid.parse>>,
    );

    render(<MermaidDiagram chart="<MagicMock id='1'>" />);

    expect(await screen.findByText(FAILED_MESSAGE)).toBeInTheDocument();
    expect(mermaid.render).not.toHaveBeenCalled();
  });

  it("should remove the error element mermaid leaves in the body when rendering fails", async () => {
    vi.mocked(mermaid.parse).mockResolvedValue(VALID_PARSE);
    vi.mocked(mermaid.render).mockImplementation(async (id: string) => {
      const leftover = document.createElement("div");
      leftover.id = `d${id}`;
      document.body.appendChild(leftover);
      throw new Error("render failed");
    });

    render(<MermaidDiagram chart="graph TD; a --> b" />);

    expect(await screen.findByText(FAILED_MESSAGE)).toBeInTheDocument();
    await waitFor(() =>
      expect(document.querySelector('[id^="dmermaid-diagram"]')).toBeNull(),
    );
  });
});
