import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import JsonTreePopover from "./JsonTreePopover";
import { JsonObject } from "@/types/shared";

const DATA: JsonObject = {
  input: { input_text: "Bonjour", tone: "neutral" },
  output: { verdict: "correct" },
};

const renderPopover = (
  props: Partial<React.ComponentProps<typeof JsonTreePopover>> = {},
) =>
  render(
    <JsonTreePopover
      data={DATA}
      open
      onOpenChange={vi.fn()}
      onSelect={vi.fn()}
      trigger={<button>open</button>}
      {...props}
    />,
  );

describe("JsonTreePopover", () => {
  it("renders the default header when no override is passed", () => {
    renderPopover();

    expect(screen.getByText("Select a variable")).toBeInTheDocument();
  });

  it("renders a custom header in place of the default", () => {
    renderPopover({ header: <div>Select a field to map to</div> });

    expect(screen.getByText("Select a field to map to")).toBeInTheDocument();
    expect(screen.queryByText("Select a variable")).not.toBeInTheDocument();
  });

  it("keeps the shared footer regardless of the header", () => {
    renderPopover({ header: <div>Select a field to map to</div> });

    expect(screen.getByText("Enter")).toBeInTheDocument();
    expect(screen.getByText("Esc")).toBeInTheDocument();
  });

  it("opens collapsed at the first entry without a selected path", () => {
    renderPopover();

    expect(screen.queryByText("verdict")).not.toBeInTheDocument();
    expect(screen.getByTestId("json-tree-node-input")).toHaveAttribute(
      "data-focused",
      "true",
    );
  });

  it("expands and focuses the selected path on open", () => {
    renderPopover({ selectedPath: "output.verdict" });

    expect(screen.getByTestId("json-tree-node-output.verdict")).toHaveAttribute(
      "data-focused",
      "true",
    );
  });

  it("selects the focused path on enter", () => {
    const onSelect = vi.fn();
    renderPopover({ onSelect });

    fireEvent.keyDown(document, { key: "Enter" });

    expect(onSelect).toHaveBeenCalledWith("input", DATA.input);
  });
});
