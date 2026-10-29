import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import DashboardWidgetHeader from "./DashboardWidgetHeader";

describe("DashboardWidgetHeader", () => {
  it("exposes the information tooltip on keyboard focus", async () => {
    render(
      <TooltipProvider delayDuration={0}>
        <DashboardWidgetHeader
          title="Span cost"
          infoMessage="Tag costs overlap"
        />
      </TooltipProvider>,
    );

    const info = screen.getByRole("button", { name: "Widget information" });
    expect(info.tabIndex).toBe(0);
    fireEvent.focus(info);
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "Tag costs overlap",
    );
  });
});
