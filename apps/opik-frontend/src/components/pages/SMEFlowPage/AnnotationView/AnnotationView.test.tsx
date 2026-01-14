import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { TooltipProvider } from "@/components/ui/tooltip";
import AnnotationView from "./AnnotationView";
import { ReactNode } from "react";

// Mock the SMEFlowContext
const mockUseSMEFlow = vi.fn();
vi.mock("../SMEFlowContext", () => ({
  useSMEFlow: () => mockUseSMEFlow(),
}));

// Mock the child components
vi.mock("./TraceDataViewer", () => ({
  default: () => <div data-testid="trace-data-viewer" />,
}));

vi.mock("./ThreadDataViewer", () => ({
  default: () => <div data-testid="thread-data-viewer" />,
}));

vi.mock("./CommentAndScoreViewer", () => ({
  default: () => <div data-testid="comment-score-viewer" />,
}));

vi.mock("./ValidationAlert", () => ({
  default: () => <div data-testid="validation-alert" />,
}));

vi.mock("../SMEFlowLayout", () => ({
  default: ({
    children,
    footer,
  }: {
    children: ReactNode;
    footer: ReactNode;
  }) => (
    <div>
      <div data-testid="layout-children">{children}</div>
      <div data-testid="layout-footer">{footer}</div>
    </div>
  ),
}));

vi.mock("../ReturnToAnnotationQueueButton", () => ({
  default: () => <div data-testid="return-button" />,
}));

vi.mock("./AnnotationTreeStateContext", () => ({
  AnnotationTreeStateProvider: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
}));

// Mock react-hotkeys-hook
vi.mock("react-hotkeys-hook", () => ({
  useHotkeys: vi.fn(),
}));

describe("AnnotationView - Button Label Logic", () => {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <TooltipProvider>{children}</TooltipProvider>
  );

  const defaultContextValue = {
    annotationQueue: {
      id: "queue-1",
      name: "Test Queue",
      scope: "TRACE",
      feedback_definition_names: ["score1"],
    },
    currentIndex: 0,
    queueItems: [
      { id: "item-1", name: "Item 1" },
      { id: "item-2", name: "Item 2" },
      { id: "item-3", name: "Item 3" },
    ],
    validationState: {
      canSubmit: true,
      errors: [],
    },
    isCurrentItemProcessed: false,
    unprocessedItems: [
      { id: "item-1", name: "Item 1" },
      { id: "item-2", name: "Item 2" },
    ],
    handleNext: vi.fn(),
    handlePrevious: vi.fn(),
    handleSubmit: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("Non-completed item scenarios", () => {
    it('should show "Submit + next" when current item is not completed and there are other unprocessed items', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: false,
        unprocessedItems: [
          { id: "item-1", name: "Item 1" },
          { id: "item-2", name: "Item 2" },
        ], // 2 unprocessed items
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Submit + next")).toBeInTheDocument();
    });

    it('should show "Submit + complete" when current item is the ONLY unprocessed item', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: false,
        unprocessedItems: [{ id: "item-1", name: "Item 1" }], // Only 1 unprocessed item
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Submit + complete")).toBeInTheDocument();
    });

    it('should show "Submit + next" when there are multiple unprocessed items (including cached changes)', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: false,
        unprocessedItems: [
          { id: "item-1", name: "Item 1" }, // Has cached changes, still unprocessed
          { id: "item-2", name: "Item 2" }, // Has cached changes, still unprocessed
          { id: "item-3", name: "Item 3" }, // Current item
        ], // 3 unprocessed items (cached items are still unprocessed)
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      // Should show "Submit + next" because there are other unprocessed items
      expect(screen.queryByText("Submit + complete")).not.toBeInTheDocument();
      expect(screen.getByText("Submit + next")).toBeInTheDocument();
    });

    it('should show "Submit + next" when there are 2 unprocessed items', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: false,
        unprocessedItems: [
          { id: "item-1", name: "Item 1" },
          { id: "item-2", name: "Item 2" },
        ], // 2 unprocessed items
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      // Should show "Submit + next" because there are still other unprocessed items
      expect(screen.queryByText("Submit + complete")).not.toBeInTheDocument();
      expect(screen.getByText("Submit + next")).toBeInTheDocument();
    });
  });

  describe("Completed item scenarios", () => {
    it('should show "Update + next" when viewing a completed item with no changes and other unprocessed items exist', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: true,
        validationState: {
          canSubmit: false, // No unsaved changes
          errors: [],
        },
        unprocessedItems: [{ id: "item-2", name: "Item 2" }],
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Update + next")).toBeInTheDocument();
      // Button should be disabled when canSubmit is false
      const button = screen.getByText("Update + next");
      expect(button).toBeDisabled();
    });

    it('should show "Update + next" when completed item has changes and there are other unprocessed items', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: true,
        validationState: {
          canSubmit: true, // Has unsaved changes
          errors: [],
        },
        unprocessedItems: [{ id: "item-2", name: "Item 2" }],
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Update + next")).toBeInTheDocument();
    });

    it('should show "Update + complete" when completed item has changes and no other unprocessed items', () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        isCurrentItemProcessed: true,
        validationState: {
          canSubmit: true, // Has unsaved changes
          errors: [],
        },
        unprocessedItems: [], // No other unprocessed items
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Update + complete")).toBeInTheDocument();
    });
  });

  describe("Edge cases", () => {
    it("should disable submit button when there are validation errors", () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        validationState: {
          canSubmit: false,
          errors: [{ type: "required", message: "Field is required" }],
        },
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      const submitButton = screen.getByText("Submit + next");
      expect(submitButton).toBeDisabled();
    });

    it("should disable Previous button when on first item", () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 0,
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      const previousButton = screen.getByText("Previous");
      expect(previousButton).toBeDisabled();
    });

    it("should disable Next button when on last item", () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 2, // Last item (queueItems has 3 items)
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      const nextButton = screen.getByText("Next");
      expect(nextButton).toBeDisabled();
    });

    it("should show validation alert when there are errors", () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        validationState: {
          canSubmit: false,
          errors: [{ type: "required", message: "Field is required" }],
        },
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByTestId("validation-alert")).toBeInTheDocument();
    });

    it("should display correct processed count", () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 1, // Second item (0-indexed, so displays as 2)
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      // queueItems has 3 items in defaultContextValue, currentIndex is 1 (second item)
      expect(screen.getByText("2 of 3")).toBeInTheDocument();
    });

    it("should display counter in green with check icon when viewing a completed item", () => {
      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 1, // Second item (0-indexed, so displays as 2)
        isCurrentItemProcessed: true,
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      // queueItems has 3 items in defaultContextValue, currentIndex is 1 (second item)
      expect(screen.getByText("2 of 3")).toBeInTheDocument();
      // Check that the counter container has the special button color class
      const counterContainer = screen.getByText("2 of 3").closest("div");
      expect(counterContainer).toHaveClass("text-[var(--special-button)]");
    });
  });

  describe("Real-world scenario: User clicks Next without submitting", () => {
    it("should handle the scenario where user adds comments and clicks Next multiple times", () => {
      // Scenario: User has 3 items
      // - Item 1: User added comments, clicked Next (cached, STILL UNPROCESSED)
      // - Item 2: User added comments, clicked Next (cached, STILL UNPROCESSED)
      // - Item 3: Current item (also unprocessed)
      // Expected: Button should show "Submit + next" NOT "Submit + complete"
      // because unprocessedItems.length = 3 (all items are still unprocessed)

      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 2, // On the last item
        isCurrentItemProcessed: false,
        unprocessedItems: [
          { id: "item-1", name: "Item 1" }, // Cached, still unprocessed
          { id: "item-2", name: "Item 2" }, // Cached, still unprocessed
          { id: "item-3", name: "Item 3" }, // Current item
        ], // 3 unprocessed items
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      // Should show "Submit + next" because unprocessedItems.length = 3
      expect(screen.getByText("Submit + next")).toBeInTheDocument();
      expect(screen.queryByText("Submit + complete")).not.toBeInTheDocument();
    });

    it("should show Submit + complete only when current is the ONLY unprocessed item", () => {
      // Scenario: All other items have been submitted (saved to backend)
      // Current item is the only unprocessed item
      // Expected: "Submit + complete"

      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 2,
        isCurrentItemProcessed: false,
        unprocessedItems: [{ id: "item-3", name: "Item 3" }], // Only 1 unprocessed
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Submit + complete")).toBeInTheDocument();
    });

    it("should show Submit + next when there are 2 unprocessed items", () => {
      // Scenario: Items 1 and 2 are not processed yet
      // User is on item 1
      // Expected: "Submit + next" because item 2 still needs to be processed

      mockUseSMEFlow.mockReturnValue({
        ...defaultContextValue,
        currentIndex: 0,
        isCurrentItemProcessed: false,
        unprocessedItems: [
          { id: "item-1", name: "Item 1" },
          { id: "item-2", name: "Item 2" },
        ], // 2 unprocessed items
      });

      render(<AnnotationView header={<div>Header</div>} />, { wrapper });

      expect(screen.getByText("Submit + next")).toBeInTheDocument();
      expect(screen.queryByText("Submit + complete")).not.toBeInTheDocument();
    });
  });
});
