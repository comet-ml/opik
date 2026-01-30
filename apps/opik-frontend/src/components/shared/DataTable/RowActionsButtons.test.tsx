import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/components/ui/tooltip";
import { RowActionsButtons, ActionButtonConfig } from "./RowActionsButtons";

const renderWithProviders = (ui: React.ReactElement) => {
  return render(<TooltipProvider>{ui}</TooltipProvider>);
};

describe("RowActionsButtons", () => {
  describe("Icon-only buttons", () => {
    it("should render icon-only buttons by default", () => {
      const mockActions: ActionButtonConfig[] = [
        { type: "edit", onClick: vi.fn() },
        { type: "delete", onClick: vi.fn() },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      const editButton = screen.getByRole("button", { name: /edit/i });
      const deleteButton = screen.getByRole("button", { name: /delete/i });

      expect(editButton).toBeInTheDocument();
      expect(deleteButton).toBeInTheDocument();
      expect(editButton).not.toHaveTextContent("Edit");
      expect(deleteButton).not.toHaveTextContent("Delete");
    });

    it("should render all action types with correct icons", () => {
      const mockActions: ActionButtonConfig[] = [
        { type: "edit", onClick: vi.fn() },
        { type: "delete", onClick: vi.fn() },
        { type: "duplicate", onClick: vi.fn() },
        { type: "restore", onClick: vi.fn() },
        { type: "external", onClick: vi.fn() },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      expect(screen.getByRole("button", { name: /edit/i })).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /delete/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /duplicate/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /restore/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /open in new tab/i }),
      ).toBeInTheDocument();
    });
  });

  describe("Buttons with labels", () => {
    it("should render buttons with labels when showLabel is true", () => {
      const mockActions: ActionButtonConfig[] = [
        {
          type: "external",
          label: "Annotate queue",
          showLabel: true,
          onClick: vi.fn(),
        },
        {
          type: "duplicate",
          label: "Copy sharing link",
          showLabel: false,
          onClick: vi.fn(),
        },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      const annotateButton = screen.getByRole("button", {
        name: /annotate queue/i,
      });
      expect(annotateButton).toBeInTheDocument();
      expect(annotateButton).toHaveTextContent("Annotate queue");

      const copyButton = screen.getByRole("button", {
        name: /copy sharing link/i,
      });
      expect(copyButton).toBeInTheDocument();
      expect(copyButton).not.toHaveTextContent("Copy sharing link");
    });

    it("should use custom labels when provided", () => {
      const mockActions: ActionButtonConfig[] = [
        { type: "duplicate", label: "Clone", onClick: vi.fn() },
        { type: "duplicate", label: "Copy sharing link", onClick: vi.fn() },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      expect(
        screen.getByRole("button", { name: /^clone$/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /copy sharing link/i }),
      ).toBeInTheDocument();
    });

    it("should use default labels when custom label is not provided", () => {
      const mockActions: ActionButtonConfig[] = [
        { type: "edit", onClick: vi.fn() },
        { type: "delete", onClick: vi.fn() },
        { type: "duplicate", onClick: vi.fn() },
        { type: "restore", onClick: vi.fn() },
        { type: "external", onClick: vi.fn() },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      expect(screen.getByRole("button", { name: /edit/i })).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /delete/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /duplicate/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /restore/i }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /open in new tab/i }),
      ).toBeInTheDocument();
    });
  });

  describe("Click handlers", () => {
    it("should call onClick handlers correctly", () => {
      const editHandler = vi.fn();
      const deleteHandler = vi.fn();

      const mockActions: ActionButtonConfig[] = [
        { type: "edit", onClick: editHandler },
        { type: "delete", onClick: deleteHandler },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      const editButton = screen.getByRole("button", { name: /edit/i });
      const deleteButton = screen.getByRole("button", { name: /delete/i });

      fireEvent.click(editButton);
      expect(editHandler).toHaveBeenCalledTimes(1);
      expect(deleteHandler).not.toHaveBeenCalled();

      fireEvent.click(deleteButton);
      expect(deleteHandler).toHaveBeenCalledTimes(1);
      expect(editHandler).toHaveBeenCalledTimes(1);
    });

    it("should handle multiple clicks correctly", () => {
      const clickHandler = vi.fn();

      const mockActions: ActionButtonConfig[] = [
        { type: "edit", onClick: clickHandler },
      ];

      renderWithProviders(<RowActionsButtons actions={mockActions} />);

      const editButton = screen.getByRole("button", { name: /edit/i });

      fireEvent.click(editButton);
      fireEvent.click(editButton);
      fireEvent.click(editButton);

      expect(clickHandler).toHaveBeenCalledTimes(3);
    });
  });

  describe("Button grouping", () => {
    it("should render multiple actions in a button group", () => {
      const mockActions: ActionButtonConfig[] = [
        { type: "edit", onClick: vi.fn() },
        { type: "duplicate", label: "Clone", onClick: vi.fn() },
        { type: "delete", onClick: vi.fn() },
      ];

      const { container } = renderWithProviders(
        <RowActionsButtons actions={mockActions} />,
      );

      const buttons = container.querySelectorAll("button");
      expect(buttons).toHaveLength(3);
    });

    it("should render empty group when no actions provided", () => {
      const { container } = renderWithProviders(
        <RowActionsButtons actions={[]} />,
      );

      const buttons = container.querySelectorAll("button");
      expect(buttons).toHaveLength(0);
    });
  });
});
