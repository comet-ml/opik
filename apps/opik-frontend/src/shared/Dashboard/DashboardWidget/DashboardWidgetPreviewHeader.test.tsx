import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import DashboardWidgetPreviewHeader from "./DashboardWidgetPreviewHeader";

vi.mock("@/store/DashboardStore", () => ({
  useDashboardStore: (selector: (state: object) => unknown) =>
    selector({
      previewWidget: { title: "Span cost", generatedTitle: "Project metrics" },
      updatePreviewWidget: vi.fn(),
    }),
  selectPreviewWidget: (state: { previewWidget: object }) =>
    state.previewWidget,
  selectUpdatePreviewWidget: (state: { updatePreviewWidget: unknown }) =>
    state.updatePreviewWidget,
}));

describe("DashboardWidgetPreviewHeader", () => {
  it("opens only the information tooltip and keeps title editing separate", async () => {
    render(
      <TooltipProvider delayDuration={0}>
        <DashboardWidgetPreviewHeader infoMessage="Tag costs overlap" />
      </TooltipProvider>,
    );

    const info = screen.getByRole("button", { name: "Widget information" });
    fireEvent.focus(info);
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "Tag costs overlap",
    );
    expect(screen.getAllByRole("tooltip")).toHaveLength(1);
    fireEvent.click(info);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Span cost" }));
    expect(screen.getByRole("textbox")).toHaveValue("Span cost");
  });
});
