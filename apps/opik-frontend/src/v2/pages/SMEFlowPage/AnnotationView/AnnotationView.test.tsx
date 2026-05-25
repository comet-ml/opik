import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import AnnotationView from "./AnnotationView";
import { ReactNode } from "react";
import { ITEM_STATE } from "../SMEFlowContext";

const mockUseSMEFlow = vi.fn();
vi.mock("../SMEFlowContext", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../SMEFlowContext")>();
  return {
    ...actual,
    useSMEFlow: () => mockUseSMEFlow(),
  };
});

vi.mock("./TraceDataViewer", () => ({
  default: {
    Header: () => <div data-testid="trace-header" />,
    Content: () => <div data-testid="trace-content" />,
  },
}));

vi.mock("./ThreadDataViewer", () => ({
  default: {
    Header: () => <div data-testid="thread-header" />,
    Content: () => <div data-testid="thread-content" />,
  },
}));

vi.mock("./CommentAndScoreViewer", () => ({
  default: () => <div data-testid="comment-score-viewer" />,
}));

vi.mock("./ItemsSidebar", () => ({
  default: () => <div data-testid="items-sidebar" />,
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

vi.mock("react-hotkeys-hook", () => ({
  useHotkeys: vi.fn(),
}));

describe("AnnotationView", () => {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <TooltipProvider>{children}</TooltipProvider>
  );

  const defaultContextValue = {
    annotationQueue: {
      id: "queue-1",
      name: "Test Queue",
      scope: "trace",
      feedback_definition_names: ["score1"],
    },
    currentItem: { id: "item-1", name: "Item 1" },
    nextDefaultItem: { id: "item-2", name: "Item 2" },
    itemStates: {
      "item-1": ITEM_STATE.DEFAULT,
      "item-2": ITEM_STATE.DEFAULT,
      "item-3": ITEM_STATE.SCORED,
    },
    handleNextDefault: vi.fn(),
    setCurrentView: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should render the sidebar", () => {
    mockUseSMEFlow.mockReturnValue(defaultContextValue);
    render(<AnnotationView header={<div>Header</div>} />, { wrapper });
    expect(screen.getByTestId("items-sidebar")).toBeInTheDocument();
  });

  it("should enable Next button when there are more default items", () => {
    mockUseSMEFlow.mockReturnValue(defaultContextValue);
    render(<AnnotationView header={<div>Header</div>} />, { wrapper });
    const nextButton = screen.getByText("Next");
    expect(nextButton).not.toBeDisabled();
  });

  it("should disable Next button when current is the only default item", () => {
    mockUseSMEFlow.mockReturnValue({
      ...defaultContextValue,
      nextDefaultItem: undefined,
      itemStates: {
        "item-1": ITEM_STATE.DEFAULT,
        "item-2": ITEM_STATE.COMPLETED,
        "item-3": ITEM_STATE.SCORED,
      },
    });
    render(<AnnotationView header={<div>Header</div>} />, { wrapper });
    const nextButton = screen.getByText("Next");
    expect(nextButton).toBeDisabled();
  });

  it('should show "Finish annotating" when all items are done', () => {
    mockUseSMEFlow.mockReturnValue({
      ...defaultContextValue,
      nextDefaultItem: undefined,
      itemStates: {
        "item-1": ITEM_STATE.SCORED,
        "item-2": ITEM_STATE.COMPLETED,
        "item-3": ITEM_STATE.SCORED,
      },
    });
    render(<AnnotationView header={<div>Header</div>} />, { wrapper });
    expect(screen.getByText("Finish annotating")).toBeInTheDocument();
    expect(screen.queryByText("Next")).not.toBeInTheDocument();
  });

  it("should show thread viewer when scope is thread", () => {
    mockUseSMEFlow.mockReturnValue({
      ...defaultContextValue,
      annotationQueue: {
        ...defaultContextValue.annotationQueue,
        scope: "thread",
      },
    });
    render(<AnnotationView header={<div>Header</div>} />, { wrapper });
    expect(screen.getByTestId("thread-content")).toBeInTheDocument();
  });

  it("should show trace viewer when scope is trace", () => {
    mockUseSMEFlow.mockReturnValue(defaultContextValue);
    render(<AnnotationView header={<div>Header</div>} />, { wrapper });
    expect(screen.getByTestId("trace-content")).toBeInTheDocument();
  });
});
