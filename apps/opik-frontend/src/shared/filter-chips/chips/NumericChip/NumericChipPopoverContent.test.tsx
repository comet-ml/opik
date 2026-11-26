import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Popover, PopoverContent } from "@/ui/popover";
import { COLUMN_TYPE } from "@/types/shared";
import { NumericChipDefinition } from "@/shared/filter-chips/types";
import NumericChipPopoverContent from "./NumericChipPopoverContent";

const DEFINITION: NumericChipDefinition = {
  id: "usage_total_tokens",
  field: "usage.total_tokens",
  label: "Total tokens",
  kind: "numeric",
  columnType: COLUMN_TYPE.number,
};

const renderBetween = (inline: boolean) => {
  const onApply = vi.fn();
  const onClear = vi.fn();
  const content = (
    <NumericChipPopoverContent
      inline={inline}
      definition={DEFINITION}
      value={{ mode: "between", min: 1, max: 5 }}
      onApply={onApply}
      onClear={onClear}
    />
  );
  render(
    inline ? (
      content
    ) : (
      <Popover open>
        <PopoverContent>{content}</PopoverContent>
      </Popover>
    ),
  );
  const [from] = screen.getAllByRole("spinbutton");
  fireEvent.change(from, { target: { value: "10" } });
  return { onApply, onClear };
};

describe("NumericChipPopoverContent", () => {
  it("clears an inline filter edited into an inverted range, so the old one is not saved", async () => {
    const { onApply, onClear } = renderBetween(true);

    await waitFor(() => expect(onClear).toHaveBeenCalled());
    expect(onApply).not.toHaveBeenCalled();
  });

  it("keeps the popover's last valid filter while a range is inverted", async () => {
    const { onApply, onClear } = renderBetween(false);

    // Long enough for the input's debounce to fire.
    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(onClear).not.toHaveBeenCalled();
    expect(onApply).not.toHaveBeenCalled();
  });
});
