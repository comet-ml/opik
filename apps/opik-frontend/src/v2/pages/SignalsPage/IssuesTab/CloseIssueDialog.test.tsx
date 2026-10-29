import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import CloseIssueDialog from "./CloseIssueDialog";

const renderDialog = (isPending: boolean) => {
  const setOpen = vi.fn();
  render(
    <CloseIssueDialog
      open
      setOpen={setOpen}
      onConfirm={vi.fn()}
      isPending={isPending}
    />,
  );
  return setOpen;
};

describe("CloseIssueDialog", () => {
  it("can't be dismissed while the close is saving", () => {
    const setOpen = renderDialog(true);

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(setOpen).not.toHaveBeenCalled();
  });

  it("keeps the sent choice while the close is saving", () => {
    renderDialog(true);

    fireEvent.click(screen.getByText("Not useful"));

    expect(screen.getByRole("radio", { name: /Resolved/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Not useful/ })).toBeDisabled();
  });

  it("dismisses with Esc otherwise", () => {
    const setOpen = renderDialog(false);

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });

    expect(setOpen).toHaveBeenCalledWith(false);
  });
});
