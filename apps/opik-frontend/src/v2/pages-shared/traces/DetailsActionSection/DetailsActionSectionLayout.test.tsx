import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { Dialog, DialogContent, DialogTitle } from "@/ui/dialog";
import DetailsActionSectionLayout from "./DetailsActionSectionLayout";
import { DetailsActionSection } from "./types";

const ESCAPE = { key: "Escape", code: "Escape" };

const renderSection = (withDialog: boolean) => {
  const setActiveSection = vi.fn();
  render(
    <TooltipProvider>
      <DetailsActionSectionLayout
        title="Annotate"
        activeSection={DetailsActionSection.Annotate}
        setActiveSection={setActiveSection}
      >
        <button>Section body</button>
      </DetailsActionSectionLayout>
      {withDialog && (
        <Dialog open>
          <DialogContent aria-describedby={undefined}>
            <DialogTitle>Delete trace</DialogTitle>
            <button>Cancel</button>
          </DialogContent>
        </Dialog>
      )}
    </TooltipProvider>,
  );
  return setActiveSection;
};

describe("DetailsActionSectionLayout Escape hotkey", () => {
  it("closes the section on Escape pressed in it", () => {
    const setActiveSection = renderSection(false);

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Section body" }),
      ESCAPE,
    );

    expect(setActiveSection).toHaveBeenCalledWith(null);
  });

  it("stays open on Escape pressed in a dialog opened over it", () => {
    const setActiveSection = renderSection(true);

    fireEvent.keyDown(screen.getByRole("button", { name: "Cancel" }), ESCAPE);

    expect(setActiveSection).not.toHaveBeenCalled();
  });
});
