import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import ErrorInfoFieldSelect from "@/shared/FiltersContent/ErrorInfoFieldSelect";
import { DropdownOption } from "@/types/shared";

vi.mock("@/shared/SelectBox/SelectBox", () => ({
  default: ({
    value,
    options,
    onChange,
  }: {
    value: string;
    options: DropdownOption<string>[];
    onChange: (value: string) => void;
  }) => (
    <div>
      <span data-testid="selected-value">{value}</span>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  ),
}));

describe("ErrorInfoFieldSelect", () => {
  it("selects a field and clears back to all fields", () => {
    const onValueChange = vi.fn();

    render(
      <ErrorInfoFieldSelect value="message" onValueChange={onValueChange} />,
    );

    expect(screen.getByTestId("selected-value")).toHaveTextContent("message");

    fireEvent.click(screen.getByRole("button", { name: "Traceback" }));
    expect(onValueChange).toHaveBeenCalledWith("traceback");

    fireEvent.click(screen.getByRole("button", { name: "All fields" }));
    expect(onValueChange).toHaveBeenCalledWith("");
  });
});
