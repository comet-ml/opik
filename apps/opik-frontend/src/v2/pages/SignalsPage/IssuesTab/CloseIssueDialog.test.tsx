import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import CloseIssueDialog from "./CloseIssueDialog";

const renderDialog = (isPending: boolean) => {
  const setOpen = vi.fn();
  render(
    <CloseIssueDialog
      open
      setOpen={setOpen}
      issueName="Agent tool loop"
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

  it("dismisses with Esc otherwise", () => {
    const setOpen = renderDialog(false);

    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });

    expect(setOpen).toHaveBeenCalledWith(false);
  });
});
