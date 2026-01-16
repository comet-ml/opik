import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import AutoComplete from "./Autocomplete";

describe("AutoComplete - Clear button functionality", () => {
  const mockOnValueChange = vi.fn();
  const defaultItems = ["option1", "option2", "option3"];

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render clear button when value is provided", () => {
    render(
      <AutoComplete
        value="test-value"
        onValueChange={mockOnValueChange}
        items={defaultItems}
      />,
    );

    const clearButton = screen.getByRole("button", { hidden: true });
    expect(clearButton).toBeInTheDocument();
  });

  it("should not render clear button when value is empty", () => {
    render(
      <AutoComplete
        value=""
        onValueChange={mockOnValueChange}
        items={defaultItems}
      />,
    );

    const clearButton = screen.queryByRole("button", { hidden: true });
    expect(clearButton).not.toBeInTheDocument();
  });

  it("should clear value when clear button is clicked", async () => {
    render(
      <AutoComplete
        value="test-value"
        onValueChange={mockOnValueChange}
        items={defaultItems}
      />,
    );

    const clearButton = screen.getByRole("button", { hidden: true });
    fireEvent.click(clearButton);

    await waitFor(() => {
      expect(mockOnValueChange).toHaveBeenCalledWith("");
    });
  });
});
