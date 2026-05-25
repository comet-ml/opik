import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/shared/FiltersContent/OperatorSelector", () => ({
  default: ({ operator }: { operator: string }) => (
    <button type="button">{operator}</button>
  ),
}));

vi.mock("@/shared/DebounceInput/DebounceInput", () => ({
  default: ({ value }: { value: string | number }) => (
    <input aria-label="value" readOnly value={value} />
  ),
}));

import StringRow from "@/shared/FiltersContent/rows/StringRow";
import { Filter, FilterKeySelectorComponentProps } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";

const KeySelector = ({
  value,
  onValueChange,
  placeholder,
  "data-testid": dataTestId,
}: FilterKeySelectorComponentProps) => (
  <div>
    <span data-testid="selected-key">{value || "all"}</span>
    <span data-testid={dataTestId}>{placeholder ?? "default-placeholder"}</span>
    <button type="button" onClick={() => onValueChange("traceback")}>
      Traceback
    </button>
    <button type="button" onClick={() => onValueChange("")}>
      All fields
    </button>
  </div>
);

describe("StringRow", () => {
  it("updates and clears the optional key selector value", () => {
    const filter: Filter = {
      id: "filter-1",
      field: "error_info",
      type: COLUMN_TYPE.errors,
      operator: "contains",
      key: "message",
      value: "CancelledError",
    };
    const onChange = vi.fn();

    render(
      <table>
        <tbody>
          <tr>
            <StringRow
              filter={filter}
              onChange={onChange}
              config={{ keySelectorComponent: KeySelector }}
            />
          </tr>
        </tbody>
      </table>,
    );

    expect(screen.getByTestId("selected-key")).toHaveTextContent("message");

    fireEvent.click(screen.getByRole("button", { name: "Traceback" }));
    expect(onChange).toHaveBeenCalledWith({ ...filter, key: "traceback" });

    fireEvent.click(screen.getByRole("button", { name: "All fields" }));
    expect(onChange).toHaveBeenCalledWith({ ...filter, key: "" });
  });

  it("keeps required key selector props controlled by the row", () => {
    const filter: Filter = {
      id: "filter-1",
      field: "error_info",
      type: COLUMN_TYPE.errors,
      operator: "contains",
      key: "message",
      value: "CancelledError",
    };

    render(
      <table>
        <tbody>
          <tr>
            <StringRow
              filter={filter}
              onChange={vi.fn()}
              config={{
                keySelectorComponent: KeySelector,
                keySelectorComponentProps: {
                  placeholder: "All fields",
                },
              }}
            />
          </tr>
        </tbody>
      </table>,
    );

    expect(screen.getByTestId("selected-key")).toHaveTextContent("message");
    expect(screen.getByTestId("filter-string-key-input")).toHaveTextContent(
      "All fields",
    );
  });
});
